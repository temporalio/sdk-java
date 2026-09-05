package io.temporal.internal.common.converter;

import com.google.common.reflect.TypeToken;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.failure.v1.Failure;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DataConverterException;
import io.temporal.common.converter.RawValue;
import io.temporal.common.converter.TemporalTransferTypeConverter;
import io.temporal.common.converter.TransferTypeConverter;
import io.temporal.payload.context.SerializationContext;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Optional;
import javax.annotation.Nonnull;

/** Applies type-owned transfer conversion around an SDK-managed data converter. */
public final class TemporalTransferTypeDataConverter implements DataConverter {
  private static final ClassValue<ConverterDescriptor> CONVERTER_DESCRIPTORS =
      new ClassValue<ConverterDescriptor>() {
        @Override
        protected ConverterDescriptor computeValue(Class<?> type) {
          TemporalTransferTypeConverter annotation =
              type.getDeclaredAnnotation(TemporalTransferTypeConverter.class);
          return annotation == null
              ? ConverterDescriptor.NONE
              : new ConverterDescriptor(type, annotation.value());
        }
      };

  private final DataConverter delegate;

  private TemporalTransferTypeDataConverter(DataConverter delegate) {
    this.delegate = delegate;
  }

  /** Wraps a converter once. */
  public static DataConverter wrap(DataConverter converter) {
    if (converter instanceof TemporalTransferTypeDataConverter) {
      return converter;
    }
    return new TemporalTransferTypeDataConverter(converter);
  }

  @Override
  public <T> Optional<Payload> toPayload(T value) throws DataConverterException {
    return delegate.toPayload(toTransferValue(value));
  }

  @Override
  public Optional<Payloads> toPayloads(Object... values) throws DataConverterException {
    if (values == null) {
      return delegate.toPayloads(values);
    }
    Object[] transferred = new Object[values.length];
    for (int i = 0; i < values.length; i++) {
      transferred[i] = toTransferValue(values[i]);
    }
    return delegate.toPayloads(transferred);
  }

  @Override
  public <T> T fromPayload(Payload payload, Class<T> valueClass, Type valueType)
      throws DataConverterException {
    ConverterDescriptor descriptor = descriptorFor(valueClass);
    if (descriptor == null) {
      return delegate.fromPayload(payload, valueClass, valueType);
    }
    Type requestedType = requestedType(valueClass, valueType);
    TransferType transfer = descriptor.transferTypeFor(requestedType);
    Object value = delegate.fromPayload(payload, transfer.rawType, transfer.type);
    return valueClass.cast(fromTransferValue(value, descriptor, requestedType));
  }

  @Override
  public <T> T fromPayloads(
      int index, Optional<Payloads> content, Class<T> valueClass, Type valueType)
      throws DataConverterException {
    ConverterDescriptor descriptor = descriptorFor(valueClass);
    if (descriptor == null || !hasPayload(index, content)) {
      return delegate.fromPayloads(index, content, valueClass, valueType);
    }
    Type requestedType = requestedType(valueClass, valueType);
    TransferType transfer = descriptor.transferTypeFor(requestedType);
    Object value = delegate.fromPayloads(index, content, transfer.rawType, transfer.type);
    return valueClass.cast(fromTransferValue(value, descriptor, requestedType));
  }

  @Override
  public Object[] fromPayloads(
      Optional<Payloads> content, Class<?>[] parameterTypes, Type[] genericParameterTypes)
      throws DataConverterException {
    if (parameterTypes != null
        && (genericParameterTypes == null
            || parameterTypes.length != genericParameterTypes.length)) {
      throw new IllegalArgumentException(
          "parameterTypes don't match length of valueTypes: "
              + Arrays.toString(parameterTypes)
              + "<>"
              + Arrays.toString(genericParameterTypes));
    }
    if (!content.isPresent() || content.get().getPayloadsCount() == 0) {
      return delegate.fromPayloads(content, parameterTypes, genericParameterTypes);
    }

    Class<?>[] transferClasses = parameterTypes.clone();
    Type[] transferTypes = genericParameterTypes.clone();
    ConverterDescriptor[] descriptors = new ConverterDescriptor[parameterTypes.length];
    int payloadCount = content.get().getPayloadsCount();

    for (int i = 0; i < parameterTypes.length; i++) {
      ConverterDescriptor descriptor = descriptorFor(parameterTypes[i]);
      descriptors[i] = descriptor;
      if (descriptor != null && i < payloadCount) {
        Type requestedType = requestedType(parameterTypes[i], genericParameterTypes[i]);
        TransferType transfer = descriptor.transferTypeFor(requestedType);
        transferClasses[i] = transfer.rawType;
        transferTypes[i] = transfer.type;
      }
    }

    Object[] values = delegate.fromPayloads(content, transferClasses, transferTypes);
    for (int i = 0; i < values.length && i < payloadCount; i++) {
      if (descriptors[i] != null) {
        Type requestedType = requestedType(parameterTypes[i], genericParameterTypes[i]);
        values[i] = fromTransferValue(values[i], descriptors[i], requestedType);
      }
    }
    return values;
  }

  @Nonnull
  @Override
  public RuntimeException failureToException(@Nonnull Failure failure) {
    return delegate.failureToException(failure);
  }

  @Nonnull
  @Override
  public Failure exceptionToFailure(@Nonnull Throwable throwable) {
    return delegate.exceptionToFailure(throwable);
  }

  @Nonnull
  @Override
  public DataConverter withContext(@Nonnull SerializationContext context) {
    return wrap(delegate.withContext(context));
  }

  private static Object toTransferValue(Object value) {
    if (value == null || value instanceof RawValue) {
      return value;
    }
    ConverterDescriptor descriptor = descriptorFor(value.getClass());
    return descriptor == null ? value : descriptor.converter().toTransferType(value);
  }

  private static Object fromTransferValue(
      Object value, ConverterDescriptor descriptor, Type valueType) {
    return descriptor.converter().fromTransferType(value, valueType);
  }

  private static Type requestedType(Class<?> valueClass, Type valueType) {
    return valueType == null ? valueClass : valueType;
  }

  private static ConverterDescriptor descriptorFor(Class<?> valueClass) {
    if (valueClass == RawValue.class) {
      return null;
    }
    ConverterDescriptor descriptor = CONVERTER_DESCRIPTORS.get(valueClass);
    return descriptor == ConverterDescriptor.NONE ? null : descriptor;
  }

  private static boolean hasPayload(int index, Optional<Payloads> content) {
    return content.isPresent() && index >= 0 && index < content.get().getPayloadsCount();
  }

  private static final class TransferType {
    private final Type type;
    private final Class<Object> rawType;

    @SuppressWarnings("unchecked")
    private TransferType(Type type) {
      this.type = type;
      this.rawType = (Class<Object>) TypeToken.of(type).getRawType();
    }
  }

  /**
   * Owns the transfer converter declaration and its lazily initialized converter instance for a
   * model class.
   *
   * <p>The {@link ClassValue} cache publishes one descriptor to callers before converter
   * construction. Synchronizing initialization on that shared descriptor prevents concurrent
   * callers from constructing duplicate converter instances. Once construction succeeds, every
   * caller reuses the same instance.
   */
  private static final class ConverterDescriptor {
    /** Represents cached absence while keeping descriptor lookup results non-null. */
    private static final ConverterDescriptor NONE = new ConverterDescriptor();

    private final Class<?> modelClass;
    private final Class<? extends TransferTypeConverter<?>> converterClass;
    private volatile TransferTypeConverter<Object> converter;

    private ConverterDescriptor() {
      this.modelClass = null;
      this.converterClass = null;
    }

    private ConverterDescriptor(
        Class<?> modelClass, Class<? extends TransferTypeConverter<?>> converterClass) {
      this.modelClass = modelClass;
      this.converterClass = converterClass;
    }

    private TransferType transferTypeFor(Type valueType) {
      Type type = converter().getTransferType(valueType);
      if (type == null) {
        throw new DataConverterException(
            "Transfer type converter "
                + converterClass.getName()
                + " returned a null transfer type for "
                + modelClass.getName());
      }
      return new TransferType(type);
    }

    /**
     * Returns the shared converter, constructing it on first use. The volatile fast path avoids
     * synchronization after the instance has been safely published.
     */
    @SuppressWarnings("unchecked")
    private TransferTypeConverter<Object> converter() {
      TransferTypeConverter<Object> result = converter;
      if (result != null) {
        return result;
      }
      synchronized (this) {
        if (converter == null) {
          converter = (TransferTypeConverter<Object>) instantiateConverter();
        }
        return converter;
      }
    }

    private TransferTypeConverter<?> instantiateConverter() {
      if (converterClass.isInterface() || Modifier.isAbstract(converterClass.getModifiers())) {
        throw declarationFailure("must be a concrete class", null);
      }
      if (converterClass.isMemberClass() && !Modifier.isStatic(converterClass.getModifiers())) {
        throw declarationFailure("must be static when declared as an inner class", null);
      }
      try {
        Constructor<? extends TransferTypeConverter<?>> constructor =
            converterClass.getConstructor();
        return constructor.newInstance();
      } catch (ReflectiveOperationException | SecurityException e) {
        throw declarationFailure("must have an accessible public no-argument constructor", e);
      }
    }

    private DataConverterException declarationFailure(String reason, Throwable cause) {
      String message =
          "Invalid transfer type converter "
              + converterClass.getName()
              + " declared by "
              + modelClass.getName()
              + ": "
              + reason;
      return cause == null
          ? new DataConverterException(message)
          : new DataConverterException(message, cause);
    }
  }
}
