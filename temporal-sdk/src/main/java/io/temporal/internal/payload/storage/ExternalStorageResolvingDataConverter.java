package io.temporal.internal.payload.storage;

import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.failure.v1.Failure;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DataConverterException;
import io.temporal.payload.context.SerializationContext;
import java.lang.reflect.Type;
import java.util.Optional;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Delegating {@link DataConverter} that resolves external-storage reference payloads to their
 * stored contents before deserialization. Serialization and failure conversion pass through
 * unchanged.
 */
public final class ExternalStorageResolvingDataConverter implements DataConverter {
  private final DataConverter delegate;
  private final ExternalStorage externalStorage;
  private final @Nullable SerializationContext serializationContext;

  public ExternalStorageResolvingDataConverter(
      DataConverter delegate, ExternalStorage externalStorage) {
    this(delegate, externalStorage, null);
  }

  private ExternalStorageResolvingDataConverter(
      DataConverter delegate,
      ExternalStorage externalStorage,
      @Nullable SerializationContext serializationContext) {
    this.delegate = delegate;
    this.externalStorage = externalStorage;
    this.serializationContext = serializationContext;
  }

  private DataConverter delegate() {
    return serializationContext == null ? delegate : delegate.withContext(serializationContext);
  }

  private Payload resolve(Payload payload) {
    if (!ExternalStorageReferences.isReference(payload)) {
      return payload;
    }
    Payloads resolved =
        externalStorage.retrieveBlocking(Payloads.newBuilder().addPayloads(payload).build());
    return resolved.getPayloads(0);
  }

  private Optional<Payloads> resolve(Optional<Payloads> content) {
    if (!content.isPresent() || !containsReference(content.get())) {
      return content;
    }
    return Optional.of(externalStorage.retrieveBlocking(content.get()));
  }

  private static boolean containsReference(Payloads payloads) {
    for (Payload payload : payloads.getPayloadsList()) {
      if (ExternalStorageReferences.isReference(payload)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public <T> Optional<Payload> toPayload(T value) throws DataConverterException {
    return delegate().toPayload(value);
  }

  @Override
  public <T> T fromPayload(Payload payload, Class<T> valueClass, Type valueType)
      throws DataConverterException {
    return delegate().fromPayload(resolve(payload), valueClass, valueType);
  }

  @Override
  public Optional<Payloads> toPayloads(Object... values) throws DataConverterException {
    return delegate().toPayloads(values);
  }

  @Override
  public <T> T fromPayloads(
      int index, Optional<Payloads> content, Class<T> valueType, Type valueGenericType)
      throws DataConverterException {
    return delegate().fromPayloads(index, resolve(content), valueType, valueGenericType);
  }

  @Override
  public Object[] fromPayloads(
      Optional<Payloads> content, Class<?>[] parameterTypes, Type[] genericParameterTypes)
      throws DataConverterException {
    return delegate().fromPayloads(resolve(content), parameterTypes, genericParameterTypes);
  }

  @Override
  @Nonnull
  public Failure exceptionToFailure(@Nonnull Throwable throwable) {
    return delegate().exceptionToFailure(throwable);
  }

  @Override
  @Nonnull
  public RuntimeException failureToException(@Nonnull Failure failure) {
    return delegate().failureToException(failure);
  }

  @Nonnull
  @Override
  public DataConverter withContext(@Nonnull SerializationContext context) {
    return new ExternalStorageResolvingDataConverter(delegate, externalStorage, context);
  }
}
