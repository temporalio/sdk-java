package io.temporal.workflow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeTrue;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.api.enums.v1.WorkflowTaskFailedCause;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.WorkflowTaskFailedEventAttributes;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowServiceException;
import io.temporal.client.WorkflowStub;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.internal.payload.limits.PayloadLimitViolationException;
import io.temporal.testUtils.Eventually;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * Tests worker enforcement of the namespace's payload error limits. They need a real server: the
 * in-memory test server reports no limits, so the worker would enforce none.
 */
public class PayloadLimitsTest {

  /** This size is above the server's default 2 MiB blob error limit and below gRPC's 4 MiB cap. */
  static final String OVERSIZED = repeat('x', 3 * 1024 * 1024);

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              OversizedResultWorkflowImpl.class, ActivityFailureTypeWorkflowImpl.class)
          .setActivityImplementations(new OversizedActivitiesImpl())
          .build();

  @Before
  public void requireRealServer() {
    assumeTrue(
        "Needs a server that reports payload error limits", SDKTestWorkflowRule.useExternalService);
  }

  @Test
  public void anOversizedWorkflowResultFailsTheWorkflowTaskRetryably() {
    OversizedResultWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(OversizedResultWorkflow.class);
    WorkflowClient.start(workflow::execute);
    String workflowId = WorkflowStub.fromTyped(workflow).getExecution().getWorkflowId();

    WorkflowTaskFailedEventAttributes failed =
        Eventually.assertEventually(
            Duration.ofSeconds(20),
            () -> {
              List<HistoryEvent> events =
                  testWorkflowRule.getHistoryEvents(
                      workflowId, EventType.EVENT_TYPE_WORKFLOW_TASK_FAILED);
              assertFalse(events.isEmpty());
              return events.get(0).getWorkflowTaskFailedEventAttributes();
            });

    assertEquals(
        WorkflowTaskFailedCause.WORKFLOW_TASK_FAILED_CAUSE_PAYLOADS_TOO_LARGE, failed.getCause());
    assertEquals("PayloadsTooLarge", failed.getFailure().getApplicationFailureInfo().getType());
    assertFalse(failed.getFailure().getApplicationFailureInfo().getNonRetryable());
    // Unlike the server, which would terminate it, the worker leaves the workflow running so
    // that a corrected version can be deployed.
    assertEquals(
        WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING,
        testWorkflowRule
            .getWorkflowClient()
            .getWorkflowServiceStubs()
            .blockingStub()
            .describeWorkflowExecution(
                DescribeWorkflowExecutionRequest.newBuilder()
                    .setNamespace(testWorkflowRule.getWorkflowClient().getOptions().getNamespace())
                    .setExecution(WorkflowStub.fromTyped(workflow).getExecution())
                    .build())
            .getWorkflowExecutionInfo()
            .getStatus());
    WorkflowStub.fromTyped(workflow).terminate("done");
  }

  @Test
  public void anOversizedActivityResultFailsTheActivityRetryably() {
    ActivityFailureTypeWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(ActivityFailureTypeWorkflow.class);
    assertEquals("PayloadsTooLarge retryable", workflow.execute(false));
  }

  @Test
  public void anOversizedHeartbeatFailsTheActivityRetryably() {
    ActivityFailureTypeWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(ActivityFailureTypeWorkflow.class);
    assertEquals("PayloadsTooLarge retryable", workflow.execute(true));
  }

  @Test
  public void clientCallsOverTheErrorLimitAreSentNotRejectedLocally() {
    WorkflowStub workflow =
        testWorkflowRule
            .getWorkflowClient()
            .newUntypedWorkflowStub(
                "OversizedResultWorkflow",
                WorkflowOptions.newBuilder()
                    .setTaskQueue(testWorkflowRule.getTaskQueue())
                    .setWorkflowRunTimeout(Duration.ofSeconds(10))
                    .build());
    // The client only warns; the server is the one that rejects the oversized input.
    WorkflowServiceException e =
        assertThrows(WorkflowServiceException.class, () -> workflow.start(OVERSIZED));
    assertFalse(PayloadLimitViolationException.find(e).isPresent());
  }

  @WorkflowInterface
  public interface OversizedResultWorkflow {
    @WorkflowMethod
    String execute();
  }

  public static class OversizedResultWorkflowImpl implements OversizedResultWorkflow {
    @Override
    public String execute() {
      return OVERSIZED;
    }
  }

  @WorkflowInterface
  public interface ActivityFailureTypeWorkflow {
    /** Returns the activity failure's type and whether it was retryable. */
    @WorkflowMethod
    String execute(boolean heartbeat);
  }

  public static class ActivityFailureTypeWorkflowImpl implements ActivityFailureTypeWorkflow {
    private final OversizedActivities activities =
        Workflow.newActivityStub(
            OversizedActivities.class,
            ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(10))
                .setHeartbeatTimeout(Duration.ofSeconds(5))
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                .build());

    @Override
    public String execute(boolean heartbeat) {
      try {
        if (heartbeat) {
          activities.heartbeatOversized();
        } else {
          activities.returnOversized();
        }
        return "completed";
      } catch (ActivityFailure e) {
        return describe(e.getCause());
      }
    }
  }

  @ActivityInterface
  public interface OversizedActivities {
    @ActivityMethod
    String returnOversized();

    @ActivityMethod
    void heartbeatOversized();
  }

  public static class OversizedActivitiesImpl implements OversizedActivities {
    @Override
    public String returnOversized() {
      return OVERSIZED;
    }

    @Override
    public void heartbeatOversized() {
      Activity.getExecutionContext().heartbeat(OVERSIZED);
    }
  }

  static String describe(Throwable failure) {
    if (failure instanceof ApplicationFailure) {
      ApplicationFailure applicationFailure = (ApplicationFailure) failure;
      return applicationFailure.getType()
          + (applicationFailure.isNonRetryable() ? " non-retryable" : " retryable");
    }
    return failure == null ? "null" : failure.getClass().getSimpleName();
  }

  private static String repeat(char c, int count) {
    char[] chars = new char[count];
    Arrays.fill(chars, c);
    return new String(chars);
  }
}
