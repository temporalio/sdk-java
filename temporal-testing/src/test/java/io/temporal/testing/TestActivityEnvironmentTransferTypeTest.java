package io.temporal.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.StringValue;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.LocalActivityOptions;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.SimplePlugin;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DataConverterException;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.converter.TransferTypeConverter;
import io.temporal.common.converter.TransferTypeConvertible;
import io.temporal.payload.context.SerializationContext;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;
import org.junit.jupiter.api.Test;

class TestActivityEnvironmentTransferTypeTest {

  @Test
  void activityContextExposesConfiguredConverter() {
    TrackingDataConverter trackingConverter = new TrackingDataConverter();
    TestActivityEnvironment environment = newEnvironment(trackingConverter);
    try {
      environment.registerActivitiesImplementations(new ConverterActivityImpl(trackingConverter));
      ConverterActivity activity = environment.newActivityStub(ConverterActivity.class);

      assertTrue(activity.usesConfiguredConverter());
    } finally {
      environment.close();
    }
  }

  @Test
  void wrapsConfiguredConverterForArgumentsAndResults() {
    TrackingDataConverter trackingConverter = new TrackingDataConverter();
    TestActivityEnvironment environment = newEnvironment(trackingConverter);
    try {
      environment.registerActivitiesImplementations(new TransferActivityImpl());
      TransferActivity activity = environment.newActivityStub(TransferActivity.class);

      TransferModel result = activity.execute(new TransferModel("value"));
      assertEquals(new TransferModel("value-result"), result);
      assertTrue(result.wasTransferred());
      assertTrue(trackingConverter.toPayloadCalls >= 2);
      assertTrue(trackingConverter.fromPayloadCalls >= 2);
    } finally {
      environment.close();
    }
  }

  @Test
  void wrapsConfiguredConverterForLocalActivityArgumentsAndResults() {
    TrackingDataConverter trackingConverter = new TrackingDataConverter();
    TestActivityEnvironment environment = newEnvironment(trackingConverter);
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
      assertTrue(trackingConverter.toPayloadCalls >= 2);
      assertTrue(trackingConverter.fromPayloadCalls >= 2);
    } finally {
      environment.close();
    }
  }

  @Test
  void wrapsConfiguredConverterForHeartbeatDetails() {
    TrackingDataConverter trackingConverter = new TrackingDataConverter();
    TestActivityEnvironment environment = newEnvironment(trackingConverter);
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
      assertTrue(trackingConverter.toPayloadCalls >= 3);
      assertTrue(trackingConverter.fromPayloadCalls >= 3);
    } finally {
      environment.close();
    }
  }

  private TestActivityEnvironment newEnvironment(TrackingDataConverter trackingConverter) {
    DataConverter originalConverter = DefaultDataConverter.newDefaultInstance();
    TestEnvironmentOptions options =
        TestEnvironmentOptions.newBuilder()
            .setWorkflowClientOptions(
                WorkflowClientOptions.newBuilder()
                    .setDataConverter(originalConverter)
                    .setPlugins(new ConverterPlugin(trackingConverter))
                    .build())
            .build();
    assertEquals(originalConverter, options.getWorkflowClientOptions().getDataConverter());
    return TestActivityEnvironment.newInstance(options);
  }

  private static final class ConverterPlugin extends SimplePlugin {
    private final DataConverter dataConverter;

    private ConverterPlugin(DataConverter dataConverter) {
      super("test-activity-environment-converter");
      this.dataConverter = dataConverter;
    }

    @Override
    public void configureWorkflowClient(@Nonnull WorkflowClientOptions.Builder builder) {
      builder.setDataConverter(dataConverter);
    }
  }

  private static final class TrackingDataConverter implements DataConverter {
    private final DataConverter delegate = DefaultDataConverter.newDefaultInstance();
    private int toPayloadCalls;
    private int fromPayloadCalls;

    @Override
    public <T> Optional<Payload> toPayload(T value) throws DataConverterException {
      toPayloadCalls++;
      return delegate.toPayload(value);
    }

    @Override
    public <T> T fromPayload(Payload payload, Class<T> valueClass, Type valueType)
        throws DataConverterException {
      fromPayloadCalls++;
      return delegate.fromPayload(payload, valueClass, valueType);
    }

    @Override
    public Optional<Payloads> toPayloads(Object... values) throws DataConverterException {
      toPayloadCalls++;
      return delegate.toPayloads(values);
    }

    @Override
    public <T> T fromPayloads(
        int index, Optional<Payloads> content, Class<T> valueClass, Type valueType)
        throws DataConverterException {
      fromPayloadCalls++;
      return delegate.fromPayloads(index, content, valueClass, valueType);
    }

    @Override
    public DataConverter withContext(@Nonnull SerializationContext context) {
      return this;
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
      if (!input.wasTransferred()) {
        throw new IllegalStateException("Activity input did not use its transfer type converter");
      }
      return new TransferModel(input.value + "-result");
    }
  }

  @ActivityInterface
  public interface ConverterActivity {
    @ActivityMethod
    boolean usesConfiguredConverter();
  }

  private static final class ConverterActivityImpl implements ConverterActivity {
    private final DataConverter expectedConverter;

    private ConverterActivityImpl(DataConverter expectedConverter) {
      this.expectedConverter = expectedConverter;
    }

    @Override
    public boolean usesConfiguredConverter() {
      return Activity.getExecutionContext().getWorkflowClient().getOptions().getDataConverter()
          == expectedConverter;
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

  @TransferTypeConvertible(TransferModelConverter.class)
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
