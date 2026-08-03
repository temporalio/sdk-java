package io.temporal.internal.payload.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.ByteString;
import io.temporal.api.command.v1.Command;
import io.temporal.api.command.v1.ScheduleActivityTaskCommandAttributes;
import io.temporal.api.command.v1.StartChildWorkflowExecutionCommandAttributes;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.SearchAttributes;
import io.temporal.payload.storage.ExternalStorageOptions;
import io.temporal.payload.storage.StorageDriver;
import io.temporal.payload.storage.StorageDriverClaim;
import io.temporal.payload.storage.StorageDriverRetrieveContext;
import io.temporal.payload.storage.StorageDriverStoreContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;

/** Tests external storage message conversion. */
public class ExternalStorageMessageConverterTest {

  @Test
  public void storeAndRetrieveRoundTripsOverAMessage() throws Exception {
    InMemoryDriver driver = new InMemoryDriver("d1");
    ExternalStorageMessageConverter converter = converter(driver, 0);
    Payloads message =
        Payloads.newBuilder().addPayloads(payload("a")).addPayloads(payload("b")).build();

    Payloads stored = converter.store(message, null).get();

    assertNotNull(ExternalStorageReferences.tryParseReference(stored.getPayloads(0)));
    assertNotNull(ExternalStorageReferences.tryParseReference(stored.getPayloads(1)));

    Payloads retrieved = converter.retrieve(stored).get();
    assertEquals(message, retrieved);
  }

  @Test
  public void walksNestedPayloads() throws Exception {
    InMemoryDriver driver = new InMemoryDriver("d1");
    ExternalStorageMessageConverter converter = converter(driver, 0);
    Command command =
        Command.newBuilder()
            .setScheduleActivityTaskCommandAttributes(
                ScheduleActivityTaskCommandAttributes.newBuilder()
                    .setInput(Payloads.newBuilder().addPayloads(payload("deep"))))
            .build();

    Command stored = converter.store(command, null).get();

    Payload nested = stored.getScheduleActivityTaskCommandAttributes().getInput().getPayloads(0);
    assertNotNull(ExternalStorageReferences.tryParseReference(nested));
    assertEquals(command, converter.retrieve(stored).get());
  }

  @Test
  public void payloadBelowThresholdLeavesMessageUnchanged() throws Exception {
    InMemoryDriver driver = new InMemoryDriver("d1");
    ExternalStorageMessageConverter converter = converter(driver, 1024);
    Payloads message = Payloads.newBuilder().addPayloads(payload("small")).build();

    Payloads stored = converter.store(message, null).get();

    assertNull(ExternalStorageReferences.tryParseReference(stored.getPayloads(0)));
    assertEquals(message, stored);
    assertTrue(driver.storeBatchSizes.isEmpty());
  }

  @Test
  public void searchAttributesAreNotOffloaded() throws Exception {
    InMemoryDriver driver = new InMemoryDriver("d1");
    ExternalStorageMessageConverter converter = converter(driver, 0);
    Command command =
        Command.newBuilder()
            .setStartChildWorkflowExecutionCommandAttributes(
                StartChildWorkflowExecutionCommandAttributes.newBuilder()
                    .setInput(Payloads.newBuilder().addPayloads(payload("input")))
                    .setSearchAttributes(
                        SearchAttributes.newBuilder()
                            .putIndexedFields("k", payload("indexed-value"))))
            .build();

    Command stored = converter.store(command, null).get();

    StartChildWorkflowExecutionCommandAttributes attrs =
        stored.getStartChildWorkflowExecutionCommandAttributes();
    assertNotNull(ExternalStorageReferences.tryParseReference(attrs.getInput().getPayloads(0)));
    Payload indexed = attrs.getSearchAttributes().getIndexedFieldsOrThrow("k");
    assertNull(ExternalStorageReferences.tryParseReference(indexed));
    assertEquals(payload("indexed-value"), indexed);
  }

  private static ExternalStorageMessageConverter converter(StorageDriver driver, int threshold) {
    ExternalStoragePayloadConverter payloadConverter =
        ExternalStoragePayloadConverter.fromOptions(
            ExternalStorageOptions.newBuilder()
                .setDriver(driver)
                .setPayloadSizeThreshold(threshold)
                .build());
    return new ExternalStorageMessageConverter(payloadConverter, 4);
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
}
