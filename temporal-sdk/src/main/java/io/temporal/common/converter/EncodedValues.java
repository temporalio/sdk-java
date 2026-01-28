package io.temporal.common.converter;

import io.temporal.api.common.v1.Payloads;
import java.lang.reflect.Type;
import java.util.Objects;
import java.util.Optional;

public final class EncodedValues implements Values {
  private Optional<Payloads> payloads;
  private DataConverter converter;
  private final Object[] values;

  public EncodedValues(Optional<Payloads> payloads, DataConverter converter) {
    this.payloads = Objects.requireNonNull(payloads);
    this.converter = converter;
    this.values = null;
  }

  public EncodedValues(Object... values) {
    this.values = values;
    this.payloads = null;
  }

  public Optional<Payloads> toPayloads() {
    if (payloads == null) {
      if (values == null || values.length == 0) {
        payloads = Optional.empty();
      } else if (converter == null) {
        throw new IllegalStateException("converter not set");
      } else {
        payloads = converter.toPayloads(values);
      }
    }
    return payloads;
  }

  public void setDataConverter(DataConverter converter) {
    this.converter = Objects.requireNonNull(converter);
  }

  @Override
  public int getSize() {
    if (values != null) {
      return values.length;
    } else {
      if (payloads.isPresent()) {
        return payloads.get().getPayloadsCount();
      } else {
        return 0;
      }
    }
  }

  @Override
  public <T> T get(int index, Class<T> parameterType) throws DataConverterException {
    return get(index, parameterType, parameterType);
  }

  @Override
  public <T> T get(int index, Class<T> parameterType, Type genericParameterType)
      throws DataConverterException {
    if (values != null) {
      @SuppressWarnings("unchecked")
      T result = (T) values[index];
      return result;
    } else {
      if (converter == null) {
        throw new IllegalStateException("converter not set");
      }
      return converter.fromPayloads(index, payloads, parameterType, genericParameterType);
    }
  }
}
