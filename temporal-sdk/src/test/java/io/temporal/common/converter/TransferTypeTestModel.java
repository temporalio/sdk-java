package io.temporal.common.converter;

import com.google.protobuf.StringValue;
import java.lang.reflect.Type;
import java.util.Objects;

@TemporalTransferTypeConverter(TransferTypeTestModel.Converter.class)
public final class TransferTypeTestModel {
  private final String value;
  private final boolean transferred;

  public TransferTypeTestModel(String value) {
    this(value, false);
  }

  private TransferTypeTestModel(String value, boolean transferred) {
    this.value = value;
    this.transferred = transferred;
  }

  public String value() {
    return value;
  }

  public boolean wasTransferred() {
    return transferred;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof TransferTypeTestModel
        && Objects.equals(value, ((TransferTypeTestModel) other).value);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(value);
  }

  public static final class Converter implements TransferTypeConverter<TransferTypeTestModel> {
    public Converter() {}

    @Override
    public Type getTransferType(Type valueType) {
      return StringValue.class;
    }

    @Override
    public Object toTransferType(TransferTypeTestModel value) {
      return StringValue.of(value.value);
    }

    @Override
    public TransferTypeTestModel fromTransferType(Object value, Type valueType) {
      return new TransferTypeTestModel(((StringValue) value).getValue(), true);
    }
  }
}
