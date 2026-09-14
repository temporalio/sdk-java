package io.temporal.workflowstreams;

import com.google.protobuf.StringValue;
import io.temporal.common.converter.TransferTypeConverter;
import io.temporal.common.converter.TransferTypeConvertible;
import java.lang.reflect.Type;

/** Test model that proves a Workflow Stream item uses transfer conversion. */
@TransferTypeConvertible(TransferStreamTestModel.Converter.class)
public final class TransferStreamTestModel {
  private final String value;
  private final boolean transferred;

  public TransferStreamTestModel(String value) {
    this(value, false);
  }

  private TransferStreamTestModel(String value, boolean transferred) {
    this.value = value;
    this.transferred = transferred;
  }

  public boolean wasTransferred() {
    return transferred;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof TransferStreamTestModel
        && value.equals(((TransferStreamTestModel) other).value);
  }

  @Override
  public int hashCode() {
    return value.hashCode();
  }

  public static final class Converter implements TransferTypeConverter<TransferStreamTestModel> {
    public Converter() {}

    @Override
    public Type getTransferType(Type valueType) {
      return StringValue.class;
    }

    @Override
    public Object toTransferType(TransferStreamTestModel value) {
      return StringValue.of(value.value);
    }

    @Override
    public TransferStreamTestModel fromTransferType(Object value, Type valueType) {
      return new TransferStreamTestModel(((StringValue) value).getValue(), true);
    }
  }
}
