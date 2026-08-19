package io.temporal.client;

import static org.junit.Assert.assertEquals;

import io.temporal.api.common.v1.Memo;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.workflow.v1.WorkflowExecutionInfo;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.internal.payload.storage.ExternalStorageRunner;
import io.temporal.payload.storage.ExternalStorage;
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

public class WorkflowExecutionMetadataTest {

  @Test
  public void getMemoResolvesAnExternalStorageReferenceFromTheConverter() {
    ExternalStorage config =
        ExternalStorage.newBuilder()
            .setDriver(new InMemoryDriver())
            .setPayloadSizeThreshold(0)
            .build();
    DataConverter converter = DefaultDataConverter.newDefaultInstance().withExternalStorage(config);

    // Offload the memo value so the stored info holds a reference, not the inline value.
    Payloads value = converter.toPayloads("big-memo").get();
    Payloads stored = ExternalStorageRunner.create(config).storeBlocking(value, null);
    Payload reference = stored.getPayloads(0);
    WorkflowExecutionInfo info =
        WorkflowExecutionInfo.newBuilder()
            .setMemo(Memo.newBuilder().putFields("k", reference))
            .build();

    WorkflowExecutionMetadata metadata = new WorkflowExecutionMetadata(info, converter);

    assertEquals("big-memo", metadata.getMemo("k", String.class));
  }

  @Test
  public void getMemoReadsAnInlineValueWithoutExternalStorage() {
    DataConverter converter = DefaultDataConverter.newDefaultInstance();
    Payload inline = converter.toPayloads("plain").get().getPayloads(0);
    WorkflowExecutionInfo info =
        WorkflowExecutionInfo.newBuilder()
            .setMemo(Memo.newBuilder().putFields("k", inline))
            .build();

    WorkflowExecutionMetadata metadata = new WorkflowExecutionMetadata(info, converter);

    assertEquals("plain", metadata.getMemo("k", String.class));
  }

  private static final class InMemoryDriver implements StorageDriver {
    private final Map<String, Payload> objects = new HashMap<>();
    private int counter = 0;

    @Override
    public String getName() {
      return "test";
    }

    @Override
    public String getType() {
      return "test.inmemory";
    }

    @Override
    public synchronized CompletableFuture<List<StorageDriverClaim>> store(
        StorageDriverStoreContext context, List<Payload> payloads) {
      List<StorageDriverClaim> claims = new ArrayList<>();
      for (Payload payload : payloads) {
        String key = "k-" + (counter++);
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
