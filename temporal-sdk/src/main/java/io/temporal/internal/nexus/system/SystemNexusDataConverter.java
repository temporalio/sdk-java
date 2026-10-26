package io.temporal.internal.nexus.system;

import com.google.protobuf.Message;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.failure.v1.Failure;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DataConverterException;
import io.temporal.common.converter.RawValue;
import io.temporal.internal.common.converter.TemporalTransferTypeDataConverter;
import io.temporal.internal.payload.visitor.SystemNexusPayloads;
import java.lang.reflect.Type;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

/** Keeps the server-readable envelope separate from the destination workflow's user converter. */
public final class SystemNexusDataConverter implements DataConverter {
  public static final String ENDPOINT = "__temporal_system";
  private static final ThreadLocal<DataConverter> USER_CONVERTER = new ThreadLocal<>();
  private final DataConverter userConverter;

  /** Returns the user converter active in the current synchronous transfer conversion scope. */
  public static DataConverter currentDataConverter() {
    DataConverter converter = USER_CONVERTER.get();
    if (converter == null)
      throw new IllegalStateException("System Nexus user converter context is not active");
    return converter;
  }

  static <T> T withDataConverter(DataConverter converter, Supplier<T> action) {
    Objects.requireNonNull(converter, "converter");
    DataConverter previous = USER_CONVERTER.get();
    USER_CONVERTER.set(converter);
    try {
      return action.get();
    } finally {
      if (previous == null) USER_CONVERTER.remove();
      else USER_CONVERTER.set(previous);
    }
  }

  public SystemNexusDataConverter(DataConverter userConverter) {
    this.userConverter = userConverter;
  }

  @Override
  public <T> Optional<Payload> toPayload(T value) {
    if (value instanceof RawValue) return Optional.of(((RawValue) value).getPayload());
    return withDataConverter(
        userConverter,
        () -> {
          Object transferred = TemporalTransferTypeDataConverter.toTransferValue(value);
          if (!(transferred instanceof Message))
            throw new DataConverterException(
                "System Nexus inputs must have a protobuf transfer type");
          return Optional.of(SystemNexusPayloads.pack((Message) transferred));
        });
  }

  @Override
  public <T> T fromPayload(Payload payload, Class<T> valueClass, Type valueType) {
    if (valueClass == RawValue.class) return valueClass.cast(new RawValue(payload));
    return withDataConverter(
        userConverter,
        () -> {
          Message value = SystemNexusPayloads.unpack(payload).build();
          TemporalTransferTypeDataConverter.TransferType transfer =
              TemporalTransferTypeDataConverter.transferTypeFor(valueClass, valueType);
          if (transfer == null) return valueClass.cast(value);
          if (!transfer.getRawType().isInstance(value))
            throw new DataConverterException(
                "Unexpected system Nexus response type: "
                    + value.getDescriptorForType().getFullName());
          return transfer.fromValue(value, valueClass);
        });
  }

  @Override
  public Optional<Payloads> toPayloads(Object... values) {
    if (values == null || values.length == 0) return Optional.empty();
    Payloads.Builder result = Payloads.newBuilder();
    for (Object value : values) result.addPayloads(toPayload(value).get());
    return Optional.of(result.build());
  }

  @Override
  public <T> T fromPayloads(
      int index, Optional<Payloads> values, Class<T> valueClass, Type valueType) {
    if (!values.isPresent() || index >= values.get().getPayloadsCount()) return null;
    return fromPayload(values.get().getPayloads(index), valueClass, valueType);
  }

  @Nonnull
  @Override
  public RuntimeException failureToException(@Nonnull Failure failure) {
    return userConverter.failureToException(failure);
  }

  @Nonnull
  @Override
  public Failure exceptionToFailure(@Nonnull Throwable failure) {
    return userConverter.exceptionToFailure(failure);
  }
}
