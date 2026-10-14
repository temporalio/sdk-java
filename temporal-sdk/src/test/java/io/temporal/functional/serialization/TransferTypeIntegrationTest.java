package io.temporal.functional.serialization;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.common.v1.Payload;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.RetryOptions;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.converter.TransferTypeTestModel;
import io.temporal.internal.client.WorkflowClientInternal;
import io.temporal.payload.context.SerializationContext;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.Optional;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class TransferTypeIntegrationTest {
  @Parameterized.Parameters(name = "customConverter={0}")
  public static Collection<Object[]> parameters() {
    return Arrays.asList(new Object[][] {{false}, {true}});
  }

  private final DataConverter configuredConverter;

  @Rule public SDKTestWorkflowRule testWorkflowRule;

  public TransferTypeIntegrationTest(boolean customConverter) {
    configuredConverter =
        customConverter
            ? new TransferRepresentationOnlyConverter()
            : DefaultDataConverter.newDefaultInstance();
    testWorkflowRule =
        SDKTestWorkflowRule.newBuilder()
            .setWorkflowClientOptions(
                WorkflowClientOptions.newBuilder().setDataConverter(configuredConverter).build())
            .setWorkflowTypes(TransferWorkflowImpl.class, ActivityTransferWorkflowImpl.class)
            .setActivityImplementations(new TransferActivityImpl())
            .build();
  }

  @Test
  public void workflowClientAndWorkerRoundTripTransferTypes() {
    TransferWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TransferWorkflow.class);

    TransferTypeTestModel result = workflow.execute(new TransferTypeTestModel("input"));

    assertEquals(new TransferTypeTestModel("input-workflow"), result);
    assertTrue(result.wasTransferred());
  }

  @Test
  public void workflowClientKeepsConfiguredConverterAndUsesTransferAwareInternalConverter() {
    TransferTypeTestModel model = new TransferTypeTestModel("client");
    assertSame(
        configuredConverter, testWorkflowRule.getWorkflowClient().getOptions().getDataConverter());
    DataConverter internal =
        ((WorkflowClientInternal) testWorkflowRule.getWorkflowClient().getInternal())
            .getInternalDataConverter();

    Payload payload = internal.toPayload(model).get();
    assertEquals("json/protobuf", payload.getMetadataOrThrow("encoding").toStringUtf8());
    assertTrue(
        internal
            .fromPayload(payload, TransferTypeTestModel.class, TransferTypeTestModel.class)
            .wasTransferred());
  }

  @Test
  public void workflowHistoryWithTransferTypesReplays() throws Exception {
    TransferWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TransferWorkflow.class);
    workflow.execute(new TransferTypeTestModel("replay"));
    WorkflowStub untyped = WorkflowStub.fromTyped(workflow);
    WorkflowExecutionHistory history =
        testWorkflowRule.getExecutionHistory(
            untyped.getExecution().getWorkflowId(), untyped.getExecution().getRunId());

    WorkflowReplayer.replayWorkflowExecution(history, testWorkflowRule.getWorker());
  }

  @Test
  public void activityStubRoundTripsTransferTypes() {
    ActivityTransferWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(ActivityTransferWorkflow.class);

    TransferTypeTestModel result = workflow.execute(new TransferTypeTestModel("input"));

    assertEquals(new TransferTypeTestModel("input-activity-workflow"), result);
    assertTrue(result.wasTransferred());
  }

  @WorkflowInterface
  public interface TransferWorkflow {
    @WorkflowMethod
    TransferTypeTestModel execute(TransferTypeTestModel input);
  }

  public static final class TransferWorkflowImpl implements TransferWorkflow {
    @Override
    public TransferTypeTestModel execute(TransferTypeTestModel input) {
      if (!input.wasTransferred()) {
        throw new IllegalStateException("Workflow input did not use its transfer type converter");
      }
      return new TransferTypeTestModel(input.value() + "-workflow");
    }
  }

  @WorkflowInterface
  public interface ActivityTransferWorkflow {
    @WorkflowMethod
    TransferTypeTestModel execute(TransferTypeTestModel input);
  }

  public static final class ActivityTransferWorkflowImpl implements ActivityTransferWorkflow {
    private final TransferActivity activity =
        Workflow.newActivityStub(TransferActivity.class, options());

    @Override
    public TransferTypeTestModel execute(TransferTypeTestModel input) {
      if (!input.wasTransferred()) {
        throw new IllegalStateException("Workflow input did not use its transfer type converter");
      }
      TransferTypeTestModel result = activity.execute(input);
      if (!result.wasTransferred()) {
        throw new IllegalStateException("Activity result did not use its transfer type converter");
      }
      return new TransferTypeTestModel(result.value() + "-workflow");
    }
  }

  @ActivityInterface
  public interface TransferActivity {
    @ActivityMethod
    TransferTypeTestModel execute(TransferTypeTestModel input);
  }

  public static final class TransferActivityImpl implements TransferActivity {
    @Override
    public TransferTypeTestModel execute(TransferTypeTestModel input) {
      if (!input.wasTransferred()) {
        throw new IllegalStateException("Activity input did not use its transfer type converter");
      }
      return new TransferTypeTestModel(input.value() + "-activity");
    }
  }

  private static ActivityOptions options() {
    return ActivityOptions.newBuilder()
        .setStartToCloseTimeout(Duration.ofSeconds(10))
        .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
        .build();
  }

  /** Requires the SDK to convert model values before delegating to this converter. */
  private static final class TransferRepresentationOnlyConverter extends DefaultDataConverter {
    private TransferRepresentationOnlyConverter() {
      super(STANDARD_PAYLOAD_CONVERTERS);
    }

    @Override
    public <T> Optional<Payload> toPayload(T value) {
      if (value instanceof TransferTypeTestModel) {
        throw new IllegalArgumentException("Expected the transfer representation, not the model.");
      }
      return super.toPayload(value);
    }

    @Override
    public <T> T fromPayload(Payload payload, Class<T> valueClass, Type valueType) {
      if (valueClass == TransferTypeTestModel.class) {
        throw new IllegalArgumentException("Expected the transfer type, not the model type.");
      }
      return super.fromPayload(payload, valueClass, valueType);
    }

    @Override
    public DataConverter withContext(SerializationContext context) {
      return this;
    }
  }
}
