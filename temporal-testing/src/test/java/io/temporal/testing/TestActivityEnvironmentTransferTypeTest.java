package io.temporal.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.StringValue;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.LocalActivityOptions;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.converter.TemporalTransferTypeConverter;
import io.temporal.common.converter.TransferTypeConverter;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TestActivityEnvironmentTransferTypeTest {

  @Test
  void wrapsConfiguredConverterForArgumentsAndResults() {
    TestActivityEnvironment environment = newEnvironment();
    try {
      environment.registerActivitiesImplementations(new TransferActivityImpl());
      TransferActivity activity = environment.newActivityStub(TransferActivity.class);

      TransferModel result = activity.execute(new TransferModel("value"));
      assertEquals(new TransferModel("value-result"), result);
      assertTrue(result.wasTransferred());
    } finally {
      environment.close();
    }
  }

  @Test
  void wrapsConfiguredConverterForLocalActivityArgumentsAndResults() {
    TestActivityEnvironment environment = newEnvironment();
    try {
      environment.registerActivitiesImplementations(new TransferActivityImpl());
      TransferActivity activity =
          environment.newLocalActivityStub(
              TransferActivity.class,
              LocalActivityOptions.newBuilder()
                  .setScheduleToCloseTimeout(Duration.ofMinutes(1))
                  .build(),
              Collections.emptyMap());

      TransferModel result = activity.execute(new TransferModel("value"));
      assertEquals(new TransferModel("value-result"), result);
      assertTrue(result.wasTransferred());
    } finally {
      environment.close();
    }
  }

  @Test
  void wrapsConfiguredConverterForHeartbeatDetails() {
    TestActivityEnvironment environment = newEnvironment();
    try {
      environment.registerActivitiesImplementations(new HeartbeatActivityImpl());
      AtomicReference<TransferModel> heartbeat = new AtomicReference<>();
      environment.setHeartbeatDetails(new TransferModel("initial"));
      environment.setActivityHeartbeatListener(TransferModel.class, heartbeat::set);
      HeartbeatActivity activity = environment.newActivityStub(HeartbeatActivity.class);

      TransferModel result = activity.execute();
      assertEquals(new TransferModel("initial"), result);
      assertTrue(result.wasTransferred());
      assertEquals(new TransferModel("initial-heartbeat"), heartbeat.get());
      assertTrue(heartbeat.get().wasTransferred());
    } finally {
      environment.close();
    }
  }

  private TestActivityEnvironment newEnvironment() {
    TestEnvironmentOptions options =
        TestEnvironmentOptions.newBuilder()
            .setWorkflowClientOptions(
                WorkflowClientOptions.newBuilder()
                    .setDataConverter(DefaultDataConverter.newDefaultInstance())
                    .build())
            .build();
    return TestActivityEnvironment.newInstance(options);
  }

  @ActivityInterface
  public interface TransferActivity {
    @ActivityMethod
    TransferModel execute(TransferModel input);
  }

  public static final class TransferActivityImpl implements TransferActivity {
    @Override
    public TransferModel execute(TransferModel input) {
      if (!input.wasTransferred()) {
        throw new IllegalStateException("Activity input did not use its transfer type converter");
      }
      return new TransferModel(input.value + "-result");
    }
  }

  @ActivityInterface
  public interface HeartbeatActivity {
    @ActivityMethod
    TransferModel execute();
  }

  public static final class HeartbeatActivityImpl implements HeartbeatActivity {
    @Override
    public TransferModel execute() {
      Optional<TransferModel> details =
          Activity.getExecutionContext().getHeartbeatDetails(TransferModel.class);
      TransferModel value = details.orElse(null);
      if (!value.wasTransferred()) {
        throw new IllegalStateException("Heartbeat detail did not use its transfer type converter");
      }
      Activity.getExecutionContext().heartbeat(new TransferModel(value.value + "-heartbeat"));
      return value;
    }
  }

  @TemporalTransferTypeConverter(TransferModelConverter.class)
  public static final class TransferModel {
    private final String value;
    private final boolean transferred;

    private TransferModel(String value) {
      this(value, false);
    }

    private TransferModel(String value, boolean transferred) {
      this.value = value;
      this.transferred = transferred;
    }

    private boolean wasTransferred() {
      return transferred;
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
      return new TransferModel(((StringValue) value).getValue(), true);
    }
  }
}
