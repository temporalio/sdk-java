package io.temporal.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import io.temporal.api.common.v1.Memo;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.workflow.v1.WorkflowExecutionInfo;
import io.temporal.common.CancellationToken;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.internal.payload.storage.ExternalStorageDataConverter;
import io.temporal.internal.payload.storage.ExternalStorageRunner;
import io.temporal.payload.context.SerializationContext;
import io.temporal.payload.context.WorkflowSerializationContext;
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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class WorkflowExecutionMetadataTest {

  @Test
  public void getMemoResolvesAnExternalStorageReference() {
    ExternalStorage config =
        ExternalStorage.newBuilder()
            .setDriver(new InMemoryDriver())
            .setPayloadSizeThreshold(0)
            .build();
    DataConverter converter = DefaultDataConverter.newDefaultInstance();
    ExternalStorageRunner storage = ExternalStorageRunner.create(config);

    Payloads.Builder value = converter.toPayloads("big-memo").get().toBuilder();
    storage.store(value, null, null, CancellationToken.none());
    Payload reference = value.build().getPayloads(0);
    WorkflowExecutionInfo info =
        WorkflowExecutionInfo.newBuilder()
            .setMemo(Memo.newBuilder().putFields("k", reference))
            .build();

    WorkflowExecutionMetadata metadata =
        new WorkflowExecutionMetadata(info, new ExternalStorageDataConverter(converter, storage));

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

  @Test
  public void getMemoDecodesWithTheContextSuppliedByTheCaller() {
    AtomicReference<SerializationContext> seen = new AtomicReference<>();
    DataConverter base = DefaultDataConverter.newDefaultInstance();
    Payload inline = base.toPayloads("plain").get().getPayloads(0);
    WorkflowExecutionInfo info =
        WorkflowExecutionInfo.newBuilder()
            .setMemo(Memo.newBuilder().putFields("k", inline))
            .build();

    DataConverter contextual =
        new ContextRecordingDataConverter(base, null, seen)
            .withContext(new WorkflowSerializationContext("the-namespace", "wf-1"));

    assertEquals(
        "plain", new WorkflowExecutionMetadata(info, contextual).getMemo("k", String.class));

    SerializationContext used = seen.get();
    assertNotNull("the converter should have been used with a context", used);
    assertEquals(
        "the caller's namespace must survive to the codec",
        "the-namespace",
        ((WorkflowSerializationContext) used).getNamespace());
    assertEquals("wf-1", ((WorkflowSerializationContext) used).getWorkflowId());
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

  private static final class ContextRecordingDataConverter implements DataConverter {
    private final DataConverter delegate;
    private final SerializationContext context;
    private final AtomicReference<SerializationContext> seen;

    ContextRecordingDataConverter(
        DataConverter delegate,
        SerializationContext context,
        AtomicReference<SerializationContext> seen) {
      this.delegate = delegate;
      this.context = context;
      this.seen = seen;
    }

    @Override
    public DataConverter withContext(SerializationContext context) {
      return new ContextRecordingDataConverter(delegate, context, seen);
    }

    @Override
    public <T> java.util.Optional<Payload> toPayload(T value) {
      return delegate.toPayload(value);
    }

    @Override
    public <T> T fromPayload(
        Payload payload, Class<T> valueClass, java.lang.reflect.Type valueType) {
      seen.set(context);
      return delegate.fromPayload(payload, valueClass, valueType);
    }

    @Override
    public java.util.Optional<Payloads> toPayloads(Object... values) {
      return delegate.toPayloads(values);
    }

    @Override
    public <T> T fromPayloads(
        int index,
        java.util.Optional<Payloads> content,
        Class<T> parameterType,
        java.lang.reflect.Type genericParameterType) {
      seen.set(context);
      return delegate.fromPayloads(index, content, parameterType, genericParameterType);
    }

    @Override
    public io.temporal.api.failure.v1.Failure exceptionToFailure(Throwable throwable) {
      return delegate.exceptionToFailure(throwable);
    }

    @Override
    public RuntimeException failureToException(io.temporal.api.failure.v1.Failure failure) {
      return delegate.failureToException(failure);
    }
  }
}
