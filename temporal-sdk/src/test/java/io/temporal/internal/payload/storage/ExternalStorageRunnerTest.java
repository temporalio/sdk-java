package io.temporal.internal.payload.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.ByteString;
import io.temporal.api.command.v1.Command;
import io.temporal.api.command.v1.CommandOrBuilder;
import io.temporal.api.command.v1.CompleteWorkflowExecutionCommandAttributes;
import io.temporal.api.command.v1.ScheduleActivityTaskCommandAttributes;
import io.temporal.api.command.v1.ScheduleActivityTaskCommandAttributesOrBuilder;
import io.temporal.api.command.v1.StartChildWorkflowExecutionCommandAttributes;
import io.temporal.api.common.v1.ActivityType;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.SearchAttributes;
import io.temporal.api.sdk.v1.UserMetadata;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** Tests external storage message conversion. */
public class ExternalStorageRunnerTest {

  @Test
  public void storeAndRetrieveRoundTripsOverAMessage() throws Exception {
    InMemoryDriver driver = new InMemoryDriver("d1");
    ExternalStorageRunner transformer = transformer(driver, 0);
    Payloads message =
        Payloads.newBuilder().addPayloads(payload("a")).addPayloads(payload("b")).build();

    Payloads.Builder builder = message.toBuilder();
    transformer.store(builder, null, null, CancellationToken.none());
    Payloads stored = builder.build();

    assertNotNull(ExternalStorageReferences.tryParseReference(stored.getPayloads(0)));
    assertNotNull(ExternalStorageReferences.tryParseReference(stored.getPayloads(1)));

    Payloads retrieved = transformer.retrieve(stored, CancellationToken.none());
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

    Command.Builder builder = command.toBuilder();
    transformer.store(builder, null, null, CancellationToken.none());
    Command stored = builder.build();

    Payload nested = stored.getScheduleActivityTaskCommandAttributes().getInput().getPayloads(0);
    assertNotNull(ExternalStorageReferences.tryParseReference(nested));
    assertEquals(command, transformer.retrieve(stored, CancellationToken.none()));
  }

  @Test
  public void payloadBelowThresholdLeavesMessageUnchanged() throws Exception {
    InMemoryDriver driver = new InMemoryDriver("d1");
    ExternalStorageRunner transformer = transformer(driver, 1024);
    Payloads message = Payloads.newBuilder().addPayloads(payload("small")).build();

    Payloads.Builder builder = message.toBuilder();
    transformer.store(builder, null, null, CancellationToken.none());
    Payloads stored = builder.build();

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

    Command.Builder builder = command.toBuilder();
    transformer.store(builder, null, null, CancellationToken.none());
    Command stored = builder.build();

    StartChildWorkflowExecutionCommandAttributes attrs =
        stored.getStartChildWorkflowExecutionCommandAttributes();
    assertNotNull(ExternalStorageReferences.tryParseReference(attrs.getInput().getPayloads(0)));
    Payload indexed = attrs.getSearchAttributes().getIndexedFieldsOrThrow("k");
    assertNull(ExternalStorageReferences.tryParseReference(indexed));
    assertEquals(payload("indexed-value"), indexed);
  }

  @Test
  public void throwIfContainsReferenceThrowsOnANestedReference() throws Exception {
    ExternalStorageRunner transformer = transformer(new InMemoryDriver("d1"), 0);
    RespondWorkflowTaskCompletedRequest.Builder request =
        RespondWorkflowTaskCompletedRequest.newBuilder()
            .addCommands(
                Command.newBuilder()
                    .setScheduleActivityTaskCommandAttributes(
                        ScheduleActivityTaskCommandAttributes.newBuilder()
                            .setActivityId("act-1")
                            .setInput(
                                Payloads.newBuilder().addPayloads(payload("activity-input")))));
    transformer.store(request, null, null, CancellationToken.none());
    RespondWorkflowTaskCompletedRequest stored = request.build();

    assertThrows(
        ExternalStorageNotConfiguredException.class,
        () -> ExternalStorageRunner.throwIfContainsReference(stored));
  }

  @Test
  public void throwIfContainsReferenceThrowsOnReference() throws Exception {
    InMemoryDriver driver = new InMemoryDriver("d1");
    ExternalStorageRunner transformer = transformer(driver, 0);
    Payloads.Builder builder = Payloads.newBuilder().addPayloads(payload("a"));
    transformer.store(builder, null, null, CancellationToken.none());
    Payloads stored = builder.build();

    assertThrows(
        ExternalStorageNotConfiguredException.class,
        () -> ExternalStorageRunner.throwIfContainsReference(stored));
  }

  @Test
  public void throwIfContainsReferenceAllowsInlinePayloads() {
    Payloads inline = Payloads.newBuilder().addPayloads(payload("a")).build();
    ExternalStorageRunner.throwIfContainsReference(inline);
  }

  @Test
  public void storeScopesCommandTargetOverAttributesAndMetadata() {
    TargetCapturingDriver driver = new TargetCapturingDriver("d1");
    ExternalStorageRunner storage = transformer(driver, 0);

    RespondWorkflowTaskCompletedRequest.Builder request =
        RespondWorkflowTaskCompletedRequest.newBuilder()
            .addCommands(
                Command.newBuilder()
                    .setUserMetadata(
                        UserMetadata.newBuilder().setSummary(payload("activity-summary")))
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
                            .setResult(Payloads.newBuilder().addPayloads(payload("wf-result")))));

    StorageDriverTargetInfo workflowTarget =
        new StorageDriverWorkflowInfo("ns", "wf-1", "run-1", "MyWorkflow");
    MessageVisitor<StorageDriverTargetInfo> visitor =
        (current, message) -> {
          if (message instanceof CommandOrBuilder) {
            CommandOrBuilder command = (CommandOrBuilder) message;
            if (command.getAttributesCase()
                == Command.AttributesCase.SCHEDULE_ACTIVITY_TASK_COMMAND_ATTRIBUTES) {
              ScheduleActivityTaskCommandAttributesOrBuilder attrs =
                  command.getScheduleActivityTaskCommandAttributesOrBuilder();
              return new StorageDriverActivityInfo(
                  "ns", attrs.getActivityId(), null, attrs.getActivityType().getName());
            }
          }
          return current;
        };

    storage.store(request, workflowTarget, visitor, CancellationToken.none());

    assertEquals(
        new StorageDriverActivityInfo("ns", "act-1", null, "MyActivity"),
        driver.targetFor("activity-input"));
    assertEquals(
        new StorageDriverActivityInfo("ns", "act-1", null, "MyActivity"),
        driver.targetFor("activity-summary"));
    assertEquals(workflowTarget, driver.targetFor("wf-result"));
  }

  @Test
  public void callerCancellationAbortsStore() {
    ExternalStorageRunner storage = transformer(new HangingDriver("d1"), 0);
    CancelSource<CancellationException> caller = new CancelSource<>(CancellationException::new);
    caller.cancel();
    Payloads message = Payloads.newBuilder().addPayloads(payload("big")).build();

    assertThrows(
        CancellationException.class,
        () -> storage.store(message.toBuilder(), null, null, caller.token()));
  }

  @Test
  public void completedOperationsReleaseTheirCancellationRegistrations() {
    RegistrationCountingToken token = new RegistrationCountingToken();
    ExternalStorageRunner storage = transformer(new InMemoryDriver("d1"), 0);

    for (int i = 0; i < 5; i++) {
      Payloads.Builder builder = Payloads.newBuilder().addPayloads(payload("a"));
      storage.store(builder, null, null, token);
      storage.retrieve(builder.build(), token);
    }

    assertEquals(0, token.open());
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

  private static final class RegistrationCountingToken
      implements CancellationToken<CancellationException> {
    private final AtomicInteger open = new AtomicInteger();

    int open() {
      return open.get();
    }

    @Override
    public boolean isCancellationRequested() {
      return false;
    }

    @Override
    public void throwIfCancellationRequested() {}

    @Override
    public Registration onCancel(Runnable callback) {
      open.incrementAndGet();
      return open::decrementAndGet;
    }
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
