package io.temporal.workflow.activityTests;

import static org.junit.Assert.*;

import com.google.protobuf.ByteString;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.common.v1.Payload;
import io.temporal.common.converter.RawValue;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestActivities.VariousTestActivities;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class TestRawValueActivity {

  private final TestActivitiesImpl activitiesImpl = new TestActivitiesImpl();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(RawValueTestWorkflowImpl.class)
          .setActivityImplementations(activitiesImpl)
          .build();

  @Test
  public void testRawValueEndToEnd() {
    RawValueTestWorkflow workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(RawValueTestWorkflow.class);
    // Intentionally don't set an encoding to test that the payload is passed through as is.
    Payload p = Payload.newBuilder().setData(ByteString.copyFromUtf8("test")).build();
    RawValue input = new RawValue(p);
    RawValue result = workflowStub.execute(input);
    Assert.assertEquals(input.getPayload(), result.getPayload());
  }

  @WorkflowInterface
  public interface RawValueTestWorkflow {
    @WorkflowMethod
    RawValue execute(RawValue value);
  }

  public static class RawValueTestWorkflowImpl implements RawValueTestWorkflow {
    @Override
    public RawValue execute(RawValue value) {
      ActivityOptions options =
          ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(5)).build();
      VariousTestActivities activities =
          Workflow.newActivityStub(VariousTestActivities.class, options);
      return activities.rawValueActivity(value);
    }
  }
}
