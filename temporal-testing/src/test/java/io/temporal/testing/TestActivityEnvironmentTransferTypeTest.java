package io.temporal.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.protobuf.StringValue;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.converter.TemporalTransferTypeConverter;
import io.temporal.common.converter.TransferTypeConverter;
import java.lang.reflect.Type;
import org.junit.jupiter.api.Test;

class TestActivityEnvironmentTransferTypeTest {

  @Test
  void wrapsConfiguredConverterForArgumentsAndResults() {
    TestEnvironmentOptions options =
        TestEnvironmentOptions.newBuilder()
            .setWorkflowClientOptions(
                WorkflowClientOptions.newBuilder()
                    .setDataConverter(DefaultDataConverter.newDefaultInstance())
                    .build())
            .build();

    TestActivityEnvironment environment = TestActivityEnvironment.newInstance(options);
    try {
      environment.registerActivitiesImplementations(new TransferActivityImpl());
      TransferActivity activity = environment.newActivityStub(TransferActivity.class);

      assertEquals(new TransferModel("value-result"), activity.execute(new TransferModel("value")));
    } finally {
      environment.close();
    }
  }

  @ActivityInterface
  public interface TransferActivity {
    @ActivityMethod
    TransferModel execute(TransferModel input);
  }

  public static final class TransferActivityImpl implements TransferActivity {
    @Override
    public TransferModel execute(TransferModel input) {
      return new TransferModel(input.value + "-result");
    }
  }

  @TemporalTransferTypeConverter(TransferModelConverter.class)
  public static final class TransferModel {
    private final String value;

    private TransferModel(String value) {
      this.value = value;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof TransferModel && value.equals(((TransferModel) other).value);
    }

    @Override
    public int hashCode() {
      return value.hashCode();
    }
  }

  public static final class TransferModelConverter implements TransferTypeConverter<TransferModel> {
    public TransferModelConverter() {}

    @Override
    public Type getTransferType(Type valueType) {
      return StringValue.class;
    }

    @Override
    public Object toTransferType(TransferModel value) {
      return StringValue.of(value.value);
    }

    @Override
    public TransferModel fromTransferType(Object value, Type valueType) {
      return new TransferModel(((StringValue) value).getValue());
    }
  }
}
