package io.temporal.internal.payload.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.ByteString;
import io.temporal.api.command.v1.Command;
import io.temporal.api.command.v1.CompleteWorkflowExecutionCommandAttributes;
import io.temporal.api.command.v1.ScheduleActivityTaskCommandAttributes;
import io.temporal.api.command.v1.ScheduleActivityTaskCommandAttributesOrBuilder;
import io.temporal.api.command.v1.StartChildWorkflowExecutionCommandAttributes;
import io.temporal.api.common.v1.ActivityType;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.SearchAttributes;
import io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedRequest;
import io.temporal.common.CancellationToken;
import io.temporal.internal.concurrent.structured.CancelSource;
import io.temporal.internal.payload.visitor.MessageVisitor;
import io.temporal.payload.storage.ExternalStorage;
import io.temporal.payload.storage.StorageDriver;
import io.temporal.payload.storage.StorageDriverActivityInfo;
import io.temporal.payload.storage.StorageDriverClaim;
import io.temporal.payload.storage.StorageDriverRetrieveContext;
import io.temporal.payload.storage.StorageDriverStoreContext;
import io.temporal.payload.storage.StorageDriverTargetInfo;
import io.temporal.payload.storage.StorageDriverWorkflowInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;

/** Tests external storage message conversion. */
public class ExternalStorageRunnerTest {

  @Test
  public void storeAndRetrieveRoundTripsOverAMessage() throws Exception {
    InMemoryDriver driver = new InMemoryDriver("d1");
    ExternalStorageRunner transformer = transformer(driver, 0);
    Payloads message =
        Payloads.newBuilder().addPayloads(payload("a")).addPayloads(payload("b")).build();

    Payloads stored = transformer.store(message, null, CancellationToken.none()).get();

    assertNotNull(ExternalStorageReferences.tryParseReference(stored.getPayloads(0)));
    assertNotNull(ExternalStorageReferences.tryParseReference(stored.getPayloads(1)));

    Payloads retrieved = transformer.retrieve(stored, CancellationToken.none()).get();
    assertEquals(message, retrieved);
  }

  @Test
  public void walksNestedPayloads() throws Exception {
    InMemoryDriver driver = new InMemoryDriver("d1");
    ExternalStorageRunner transformer = transformer(driver, 0);
    Command command =
        Command.newBuilder()
            .setScheduleActivityTaskCommandAttributes(
                ScheduleActivityTaskCommandAttributes.newBuilder()
                    .setInput(Payloads.newBuilder().addPayloads(payload("deep"))))
            .build();

    Command stored = transformer.store(command, null, CancellationToken.none()).get();

    Payload nested = stored.getScheduleActivityTaskCommandAttributes().getInput().getPayloads(0);
    assertNotNull(ExternalStorageReferences.tryParseReference(nested));
    assertEquals(command, transformer.retrieve(stored, CancellationToken.none()).get());
  }

  @Test
  public void payloadBelowThresholdLeavesMessageUnchanged() throws Exception {
    InMemoryDriver driver = new InMemoryDriver("d1");
    ExternalStorageRunner transformer = transformer(driver, 1024);
    Payloads message = Payloads.newBuilder().addPayloads(payload("small")).build();

    Payloads stored = transformer.store(message, null, CancellationToken.none()).get();

    assertNull(ExternalStorageReferences.tryParseReference(stored.getPayloads(0)));
    assertEquals(message, stored);
    assertTrue(driver.storeBatchSizes.isEmpty());
  }

  @Test
  public void searchAttributesAreNotOffloaded() throws Exception {
    InMemoryDriver driver = new InMemoryDriver("d1");
    ExternalStorageRunner transformer = transformer(driver, 0);
    Command command =
        Command.newBuilder()
            .setStartChildWorkflowExecutionCommandAttributes(
                StartChildWorkflowExecutionCommandAttributes.newBuilder()
                    .setInput(Payloads.newBuilder().addPayloads(payload("input")))
                    .setSearchAttributes(
                        SearchAttributes.newBuilder()
                            .putIndexedFields("k", payload("indexed-value"))))
            .build();

    Command stored = transformer.store(command, null, CancellationToken.none()).get();

    StartChildWorkflowExecutionCommandAttributes attrs =
        stored.getStartChildWorkflowExecutionCommandAttributes();
    assertNotNull(ExternalStorageReferences.tryParseReference(attrs.getInput().getPayloads(0)));
    Payload indexed = attrs.getSearchAttributes().getIndexedFieldsOrThrow("k");
    assertNull(ExternalStorageReferences.tryParseReference(indexed));
    assertEquals(payload("indexed-value"), indexed);
  }

  @Test
  public void throwIfContainsReferenceThrowsOnReference() throws Exception {
    InMemoryDriver driver = new InMemoryDriver("d1");
    ExternalStorageRunner transformer = transformer(driver, 0);
    Payloads stored =
        transformer
            .store(
                Payloads.newBuilder().addPayloads(payload("a")).build(),
                null,
                CancellationToken.none())
            .get();

    ExternalStorageNotConfiguredException e =
        assertThrows(
            ExternalStorageNotConfiguredException.class,
            () -> ExternalStorageRunner.throwIfContainsReference(stored));
    assertTrue(e.getMessage(), e.getMessage().contains("[TMPRL1105]"));
  }

  @Test
  public void throwIfContainsReferenceAllowsInlinePayloads() {
    Payloads inline = Payloads.newBuilder().addPayloads(payload("a")).build();
    ExternalStorageRunner.throwIfContainsReference(inline);
  }

  @Test
  public void storeAppliesPerCommandTargetFromMessageVisitor() {
    TargetCapturingDriver driver = new TargetCapturingDriver("d1");
    ExternalStorageRunner storage = transformer(driver, 0);

    RespondWorkflowTaskCompletedRequest request =
        RespondWorkflowTaskCompletedRequest.newBuilder()
            .addCommands(
                Command.newBuilder()
                    .setScheduleActivityTaskCommandAttributes(
                        ScheduleActivityTaskCommandAttributes.newBuilder()
                            .setActivityId("act-1")
                            .setActivityType(ActivityType.newBuilder().setName("MyActivity"))
                            .setInput(
                                Payloads.newBuilder().addPayloads(payload("activity-input")))))
            .addCommands(
                Command.newBuilder()
                    .setCompleteWorkflowExecutionCommandAttributes(
                        CompleteWorkflowExecutionCommandAttributes.newBuilder()
                            .setResult(Payloads.newBuilder().addPayloads(payload("wf-result")))))
            .build();

    StorageDriverTargetInfo workflowTarget =
        new StorageDriverWorkflowInfo("ns", "wf-1", "run-1", "MyWorkflow");
    MessageVisitor<StorageDriverTargetInfo> visitor =
        (current, message) -> {
          if (message instanceof ScheduleActivityTaskCommandAttributesOrBuilder) {
            ScheduleActivityTaskCommandAttributesOrBuilder attrs =
                (ScheduleActivityTaskCommandAttributesOrBuilder) message;
            return new StorageDriverActivityInfo(
                "ns", attrs.getActivityId(), null, attrs.getActivityType().getName());
          }
          return current;
        };

    storage.storeBlocking(request, workflowTarget, visitor);

    assertEquals(
        new StorageDriverActivityInfo("ns", "act-1", null, "MyActivity"),
        driver.targetFor("activity-input"));
    assertEquals(workflowTarget, driver.targetFor("wf-result"));
  }

  @Test
  public void callerCancellationAbortsStore() {
    ExternalStorageRunner storage = transformer(new HangingDriver("d1"), 0);
    CancelSource<CancellationException> caller = new CancelSource<>(CancellationException::new);
    caller.cancel();
    Payloads message = Payloads.newBuilder().addPayloads(payload("big")).build();

    assertThrows(
        CancellationException.class, () -> storage.storeBlocking(message, null, caller.token()));
  }

  private static ExternalStorageRunner transformer(StorageDriver driver, int threshold) {
    ExternalStoragePayloadTransformer payloadTransformer =
        ExternalStoragePayloadTransformer.fromOptions(
            ExternalStorage.newBuilder()
                .setDriver(driver)
                .setPayloadSizeThreshold(threshold)
                .build());
    return new ExternalStorageRunner(payloadTransformer, 4);
  }

  private static Payload payload(String data) {
    return Payload.newBuilder().setData(ByteString.copyFromUtf8(data)).build();
  }

  private static final class InMemoryDriver implements StorageDriver {
    private final String name;
    private final Map<String, Payload> objects = new HashMap<>();
    final List<Integer> storeBatchSizes = new ArrayList<>();
    private int counter = 0;

    InMemoryDriver(String name) {
      this.name = name;
    }

    @Override
    public String getName() {
      return name;
    }

    @Override
    public String getType() {
      return "test.inmemory";
    }

    @Override
    public synchronized CompletableFuture<List<StorageDriverClaim>> store(
        StorageDriverStoreContext context, List<Payload> payloads) {
      storeBatchSizes.add(payloads.size());
      List<StorageDriverClaim> claims = new ArrayList<>();
      for (Payload payload : payloads) {
        String key = name + "-" + (counter++);
        objects.put(key, payload);
        claims.add(new StorageDriverClaim(Collections.singletonMap("key", key)));
      }
      return CompletableFuture.completedFuture(claims);
    }

    @Override
    public synchronized CompletableFuture<List<Payload>> retrieve(
        StorageDriverRetrieveContext context, List<StorageDriverClaim> claims) {
      List<Payload> payloads = new ArrayList<>();
      for (StorageDriverClaim claim : claims) {
        payloads.add(objects.get(claim.getClaimData().get("key")));
      }
      return CompletableFuture.completedFuture(payloads);
    }
  }

  private static final class TargetCapturingDriver implements StorageDriver {
    private final String name;
    private final Map<String, StorageDriverTargetInfo> targetByData = new HashMap<>();
    private int counter = 0;

    TargetCapturingDriver(String name) {
      this.name = name;
    }

    @Override
    public String getName() {
      return name;
    }

    @Override
    public String getType() {
      return "test.capture";
    }

    @Override
    public synchronized CompletableFuture<List<StorageDriverClaim>> store(
        StorageDriverStoreContext context, List<Payload> payloads) {
      List<StorageDriverClaim> claims = new ArrayList<>();
      for (Payload payload : payloads) {
        targetByData.put(payload.getData().toStringUtf8(), context.getTarget());
        claims.add(
            new StorageDriverClaim(Collections.singletonMap("key", name + "-" + (counter++))));
      }
      return CompletableFuture.completedFuture(claims);
    }

    synchronized StorageDriverTargetInfo targetFor(String data) {
      return targetByData.get(data);
    }

    @Override
    public CompletableFuture<List<Payload>> retrieve(
        StorageDriverRetrieveContext context, List<StorageDriverClaim> claims) {
      throw new UnsupportedOperationException();
    }
  }

  /** Driver whose operations never settle, so only cancellation can end a blocking call. */
  private static final class HangingDriver implements StorageDriver {
    private final String name;

    HangingDriver(String name) {
      this.name = name;
    }

    @Override
    public String getName() {
      return name;
    }

    @Override
    public String getType() {
      return "test.hanging";
    }

    @Override
    public CompletableFuture<List<StorageDriverClaim>> store(
        StorageDriverStoreContext context, List<Payload> payloads) {
      return new CompletableFuture<>();
    }

    @Override
    public CompletableFuture<List<Payload>> retrieve(
        StorageDriverRetrieveContext context, List<StorageDriverClaim> claims) {
      return new CompletableFuture<>();
    }
  }
}
