package io.temporal.testserver.functional.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.google.protobuf.ByteString;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityInfo;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatRequest;
import io.temporal.common.RetryOptions;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.failure.ActivityFailure;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.testserver.functional.common.TestActivities;
import io.temporal.testserver.functional.common.TestWorkflows;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.Rule;
import org.junit.Test;

public class ActivityHeartbeatTest {
  private static final ConcurrentLinkedQueue<Optional<Payloads>> activityHeartbeats =
      new ConcurrentLinkedQueue<>();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestWorkflow.class)
          .setActivityImplementations(new TestActivity())
          .build();

  @Test
  public void testActivityHeartbeatNoLastHeartbeatDetails() {
    // Test that when last heartbeat details are not set on failure, the test server
    // clear the heartbeat details.
    String result =
        testWorkflowRule.newWorkflowStub(TestWorkflows.WorkflowReturnsString.class).execute();
    assertEquals("", result);
    assertEquals(2, activityHeartbeats.size());
    assertFalse(activityHeartbeats.poll().isPresent());
    assertEquals(
        "heartbeat details",
        DefaultDataConverter.STANDARD_INSTANCE.fromPayloads(
            0, activityHeartbeats.poll(), String.class, String.class));
  }

  public static class TestActivity implements TestActivities.ActivityReturnsString {
    @Override
    public String execute() {
      ActivityInfo info = Activity.getExecutionContext().getInfo();
      activityHeartbeats.add(info.getHeartbeatDetails());
      // Heartbeat with the raw service stub to avoid the SDK keeping track of the heartbeat
      Activity.getExecutionContext()
          .getWorkflowClient()
          .getWorkflowServiceStubs()
          .blockingStub()
          .recordActivityTaskHeartbeat(
              RecordActivityTaskHeartbeatRequest.newBuilder()
                  .setNamespace(info.getNamespace())
                  .setTaskToken(ByteString.copyFrom(info.getTaskToken()))
                  .setDetails(
                      DefaultDataConverter.STANDARD_INSTANCE.toPayloads("heartbeat details").get())
                  .build());
      throw new IllegalStateException("simulated failure");
    }
  }

  public static class TestWorkflow implements TestWorkflows.WorkflowReturnsString {
    @Override
    public String execute() {
      ActivityOptions options =
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofSeconds(10))
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2).build())
              .build();

      try {
        Workflow.newActivityStub(TestActivities.ActivityReturnsString.class, options).execute();
      } catch (ActivityFailure e) {
        // Expected
      }
      return "";
    }
  }
}
