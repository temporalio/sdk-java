package io.temporal.internal.payload.storage;

import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.failure.v1.Failure;
import io.temporal.common.CancellationToken;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DataConverterException;
import io.temporal.payload.context.SerializationContext;
import io.temporal.payload.storage.StorageDriverTargetInfo;
import java.lang.reflect.Type;
import java.util.Optional;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A {@link DataConverter} that stores/retrieves payloads to/from external storage.
 *
 * <p>This is an internal class that is not exposed to users or workflow code. The intent is to use
 * this data converter to consolidate extstore usage within the SDK.
 */
public final class ExternalStorageDataConverter implements DataConverter {

  private final DataConverter delegate;
  private final ExternalStorageRunner externalStorage;
  private final @Nullable StorageDriverTargetInfo storageTarget;

  public ExternalStorageDataConverter(
      @Nonnull DataConverter delegate, @Nonnull ExternalStorageRunner externalStorage) {
    this(delegate, externalStorage, null);
  }

  private ExternalStorageDataConverter(
      @Nonnull DataConverter delegate,
      @Nonnull ExternalStorageRunner externalStorage,
      @Nullable StorageDriverTargetInfo storageTarget) {
    this.delegate = delegate;
    this.externalStorage = externalStorage;
    this.storageTarget = storageTarget;
  }

  public ExternalStorageDataConverter withStorageTarget(
      @Nullable StorageDriverTargetInfo storageTarget) {
    return new ExternalStorageDataConverter(delegate, externalStorage, storageTarget);
  }

  @Override
  public <T> Optional<Payload> toPayload(T value) throws DataConverterException {
    Optional<Payload> converted = delegate.toPayload(value);
    if (!converted.isPresent()) {
      return converted;
    }
    Payloads stored = store(Payloads.newBuilder().addPayloads(converted.get()).build());
    return Optional.of(stored.getPayloads(0));
  }

  @Override
  public Optional<Payloads> toPayloads(Object... values) throws DataConverterException {
    Optional<Payloads> converted = delegate.toPayloads(values);
    if (!converted.isPresent()) {
      return converted;
    }
    return Optional.of(store(converted.get()));
  }

  @Override
  public <T> T fromPayload(Payload payload, Class<T> valueClass, Type valueType)
      throws DataConverterException {
    return delegate.fromPayload(retrieve(payload), valueClass, valueType);
  }

  @Override
  public <T> T fromPayloads(
      int index, Optional<Payloads> content, Class<T> parameterType, Type genericParameterType)
      throws DataConverterException {
    if (!content.isPresent() || index >= content.get().getPayloadsCount()) {
      return delegate.fromPayloads(index, content, parameterType, genericParameterType);
    }
    Payload resolved = retrieve(content.get().getPayloads(index));
    return delegate.fromPayload(resolved, parameterType, genericParameterType);
  }

  @Override
  public Object[] fromPayloads(
      Optional<Payloads> content, Class<?>[] parameterTypes, Type[] genericParameterTypes)
      throws DataConverterException {
    if (!content.isPresent()) {
      return delegate.fromPayloads(content, parameterTypes, genericParameterTypes);
    }
    return delegate.fromPayloads(
        Optional.of(retrieveAll(content.get())), parameterTypes, genericParameterTypes);
  }

  @Override
  @Nonnull
  public RuntimeException failureToException(@Nonnull Failure failure) {
    return delegate.failureToException(retrieveMessage(failure));
  }

  @Override
  @Nonnull
  public Failure exceptionToFailure(@Nonnull Throwable throwable) {
    return storeMessage(delegate.exceptionToFailure(throwable));
  }

  @Override
  @Nonnull
  public DataConverter withContext(@Nonnull SerializationContext context) {
    return new ExternalStorageDataConverter(
        delegate.withContext(context), externalStorage, storageTarget);
  }

  private Payloads retrieveAll(Payloads payloads) {
    for (Payload payload : payloads.getPayloadsList()) {
      if (ExternalStorageReferences.isReference(payload)) {
        return retrieveMessage(payloads);
      }
    }
    return payloads;
  }

  private Payload retrieve(Payload payload) {
    if (!ExternalStorageReferences.isReference(payload)) {
      return payload;
    }
    return retrieveMessage(Payloads.newBuilder().addPayloads(payload).build()).getPayloads(0);
  }

  private Payloads store(Payloads payloads) {
    Payloads.Builder builder = payloads.toBuilder();
    externalStorage.store(builder, storageTarget, null, CancellationToken.none());
    return builder.build();
  }

  private <T extends com.google.protobuf.Message> T retrieveMessage(T message) {
    return externalStorage.retrieve(message, CancellationToken.none());
  }

  private Failure storeMessage(Failure failure) {
    Failure.Builder builder = failure.toBuilder();
    externalStorage.store(builder, storageTarget, null, CancellationToken.none());
    return builder.build();
  }
}
