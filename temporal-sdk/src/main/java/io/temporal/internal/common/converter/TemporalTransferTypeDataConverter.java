package io.temporal.internal.common.converter;

import com.google.common.reflect.TypeToken;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.failure.v1.Failure;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DataConverterException;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.converter.RawValue;
import io.temporal.common.converter.TransferTypeConverter;
import io.temporal.common.converter.TransferTypeConvertible;
import io.temporal.internal.common.HeaderUtils;
import io.temporal.payload.codec.HeaderCodecProvider;
import io.temporal.payload.codec.PayloadCodec;
import io.temporal.payload.context.SerializationContext;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Optional;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Applies type-owned transfer conversion around an SDK-managed data converter. */
public final class TemporalTransferTypeDataConverter implements DataConverter, HeaderCodecProvider {
  private static final ClassValue<ConverterDescriptor> CONVERTER_DESCRIPTORS =
      new ClassValue<ConverterDescriptor>() {
        @Override
        protected ConverterDescriptor computeValue(Class<?> type) {
          TransferTypeConvertible annotation =
              type.getDeclaredAnnotation(TransferTypeConvertible.class);
          return annotation == null ? null : new ConverterDescriptor(type, annotation.value());
        }
      };

  private final DataConverter delegate;

  private TemporalTransferTypeDataConverter(DataConverter delegate) {
    this.delegate = delegate;
  }

  @Nullable
  @Override
  public PayloadCodec getHeaderCodec() {
    return HeaderUtils.getHeaderCodec(delegate);
  }

  /** Wraps a converter once. */
  public static DataConverter wrap(DataConverter converter) {
    if (converter instanceof TemporalTransferTypeDataConverter
        || converter.getClass() == DefaultDataConverter.class) {
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
    TransferType transfer = transferTypeFor(valueClass, valueType);
    if (transfer == null) {
      return delegate.fromPayload(payload, valueClass, valueType);
    }
    Object value = delegate.fromPayload(payload, transfer.rawType, transfer.type);
    return transfer.fromValue(value, valueClass);
  }

  @Override
  public <T> T fromPayloads(
      int index, Optional<Payloads> content, Class<T> valueClass, Type valueType)
      throws DataConverterException {
    if (!hasPayload(index, content)) {
      return delegate.fromPayloads(index, content, valueClass, valueType);
    }
    TransferType transfer = transferTypeFor(valueClass, valueType);
    if (transfer == null) {
      return delegate.fromPayloads(index, content, valueClass, valueType);
    }
    Object value = delegate.fromPayloads(index, content, transfer.rawType, transfer.type);
    return transfer.fromValue(value, valueClass);
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
    // Preserve types for unannotated parameters while allowing parameterized transfer types:
    // cloning a Class[] returned by reflection would still produce a Class[].
    Type[] transferTypes =
        Arrays.copyOf(genericParameterTypes, genericParameterTypes.length, Type[].class);
    TransferType[] transfers = new TransferType[parameterTypes.length];
    int payloadCount = content.get().getPayloadsCount();

    for (int i = 0; i < parameterTypes.length && i < payloadCount; i++) {
      TransferType transfer = transferTypeFor(parameterTypes[i], genericParameterTypes[i]);
      transfers[i] = transfer;
      if (transfer != null) {
        transferClasses[i] = transfer.rawType;
        transferTypes[i] = transfer.type;
      }
    }

    Object[] values = delegate.fromPayloads(content, transferClasses, transferTypes);
    for (int i = 0; i < values.length && i < payloadCount; i++) {
      if (transfers[i] != null) {
        values[i] = transfers[i].fromValue(values[i], parameterTypes[i]);
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

  /** Applies the transfer conversion for an outbound value when its class declares one. */
  public static Object toTransferValue(Object value) {
    if (value == null || value instanceof RawValue) {
      return value;
    }
    ConverterDescriptor descriptor = descriptorFor(value.getClass());
    if (descriptor == null) {
      return value;
    }
    try {
      return descriptor.converter().toTransferType(value);
    } catch (DataConverterException e) {
      throw e;
    } catch (Exception e) {
      throw new DataConverterException("Failed to convert " + descriptor.modelClass.getName(), e);
    }
  }

  private static <T> T fromTransferValue(
      Object value, ConverterDescriptor descriptor, Type valueType, Class<T> valueClass) {
    Object result;
    try {
      result = descriptor.converter().fromTransferType(value, valueType);
    } catch (DataConverterException e) {
      throw e;
    } catch (Exception e) {
      throw new DataConverterException(
          "Failed to reconstruct " + descriptor.modelClass.getName(), e);
    }
    if (result != null && !valueClass.isInstance(result)) {
      throw new DataConverterException(
          "Transfer type converter "
              + descriptor.converterClass.getName()
              + " declared by "
              + descriptor.modelClass.getName()
              + " returned "
              + result.getClass().getName()
              + " instead of "
              + valueClass.getName());
    }
    return valueClass.cast(result);
  }

  private static Type requestedType(Class<?> valueClass, Type valueType) {
    return valueType == null ? valueClass : valueType;
  }

  /** Returns the transfer representation for an annotated model, or null otherwise. */
  public static TransferType transferTypeFor(Class<?> valueClass, Type valueType) {
    ConverterDescriptor descriptor = descriptorFor(valueClass);
    return descriptor == null
        ? null
        : descriptor.transferTypeFor(requestedType(valueClass, valueType));
  }

  private static ConverterDescriptor descriptorFor(Class<?> valueClass) {
    if (valueClass == RawValue.class) {
      return null;
    }
    return CONVERTER_DESCRIPTORS.get(valueClass);
  }

  private static boolean hasPayload(int index, Optional<Payloads> content) {
    return content.isPresent() && index >= 0 && index < content.get().getPayloadsCount();
  }

  public static final class TransferType {
    private final Type type;
    private final Class<Object> rawType;
    private final ConverterDescriptor descriptor;
    private final Type requestedType;

    @SuppressWarnings("unchecked")
    private TransferType(Type type, ConverterDescriptor descriptor, Type requestedType) {
      this.type = type;
      this.rawType = (Class<Object>) TypeToken.of(type).getRawType();
      this.descriptor = descriptor;
      this.requestedType = requestedType;
    }

    public Type getType() {
      return type;
    }

    public Class<Object> getRawType() {
      return rawType;
    }

    public <T> T fromValue(Object value, Class<T> valueClass) {
      return fromTransferValue(value, descriptor, requestedType, valueClass);
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
  @SuppressWarnings(
      "rawtypes") // A generic converter class literal cannot retain its type arguments.
  private static final class ConverterDescriptor {
    private final Class<?> modelClass;
    private final Class<? extends TransferTypeConverter> converterClass;
    private volatile TransferTypeConverter<Object> converter;

    private ConverterDescriptor(
        Class<?> modelClass, Class<? extends TransferTypeConverter> converterClass) {
      this.modelClass = modelClass;
      this.converterClass = converterClass;
    }

    private TransferType transferTypeFor(Type valueType) {
      Type type;
      try {
        type = converter().getTransferType(valueType);
      } catch (DataConverterException e) {
        throw e;
      } catch (Exception e) {
        throw new DataConverterException(
            "Failed to determine transfer type for " + modelClass.getName(), e);
      }
      // The delegate and TypeToken require a concrete type to decode the transfer representation.
      if (type == null) {
        throw new DataConverterException(
            "Transfer type converter "
                + converterClass.getName()
                + " returned a null transfer type for "
                + modelClass.getName());
      }
      return new TransferType(type, this, valueType);
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
      // Interfaces and abstract classes cannot provide the converter instance used at runtime.
      if (converterClass.isInterface() || Modifier.isAbstract(converterClass.getModifiers())) {
        throw declarationFailure("must be a concrete class", null);
      }
      // A non-static member class requires an enclosing instance that the SDK does not own.
      if (converterClass.isMemberClass() && !Modifier.isStatic(converterClass.getModifiers())) {
        throw declarationFailure("must be static when declared as an inner class", null);
      }
      for (Class<?> type = converterClass; type != null; type = type.getEnclosingClass()) {
        if (!Modifier.isPublic(type.getModifiers())) {
          throw declarationFailure("must be a public class", null);
        }
      }
      try {
        // A public no-argument constructor keeps converter creation independent of application
        // state.
        Constructor<? extends TransferTypeConverter> constructor = converterClass.getConstructor();
        return constructor.newInstance();
      } catch (InvocationTargetException e) {
        throw declarationFailure("constructor threw an exception", e.getCause());
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
