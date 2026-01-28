package io.temporal.activity;

import io.temporal.common.RetryOptions;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class ActivityInfoTest {
  public static class SerializedActivityInfo {
    public byte[] taskToken;
    public String workflowId;
    public String runId;
    public String activityId;
    public String activityType;
    public Duration scheduleToCloseTimeout;
    public Duration startToCloseTimeout;
    public Duration heartbeatTimeout;
    public String workflowType;
    public String namespace;
    public String activityTaskQueue;
    public boolean isLocal;
    public int priorityKey;
    public boolean hasRetryOptions;
    public Duration retryInitialInterval;
    public double retryBackoffCoefficient;
    public int retryMaximumAttempts;
    public Duration retryMaximumInterval;
    public String[] retryDoNotRetry;
  }

  private static final RetryOptions RETRY_OPTIONS =
      RetryOptions.newBuilder()
          .setInitialInterval(Duration.ofSeconds(2))
          .setBackoffCoefficient(1.5)
          .setMaximumAttempts(5)
          .setMaximumInterval(Duration.ofSeconds(6))
          .setDoNotRetry("DoNotRetryThisType")
          .build();
  private static final ActivityOptions ACTIVITY_OPTIONS =
      ActivityOptions.newBuilder(SDKTestOptions.newActivityOptions())
          .setRetryOptions(RETRY_OPTIONS)
          .build();
  private static final LocalActivityOptions LOCAL_ACTIVITY_OPTIONS =
      LocalActivityOptions.newBuilder(SDKTestOptions.newLocalActivityOptions())
          .setRetryOptions(RETRY_OPTIONS)
          .build();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(ActivityInfoWorkflowImpl.class)
          .setActivityImplementations(new ActivityInfoActivityImpl())
          .build();

  @Test
  public void getActivityInfo() {
    ActivityInfoWorkflow workflow = testWorkflowRule.newWorkflowStub(ActivityInfoWorkflow.class);
    SerializedActivityInfo info = workflow.getActivityInfo(false);
    // Unpredictable values
    Assert.assertTrue(info.taskToken.length > 0);
    Assert.assertFalse(info.workflowId.isEmpty());
    Assert.assertFalse(info.runId.isEmpty());
    Assert.assertFalse(info.activityId.isEmpty());
    // Predictable values
    Assert.assertEquals(ActivityInfoActivity.ACTIVITY_NAME, info.activityType);
    Assert.assertEquals(ACTIVITY_OPTIONS.getScheduleToCloseTimeout(), info.scheduleToCloseTimeout);
    Assert.assertEquals(ACTIVITY_OPTIONS.getStartToCloseTimeout(), info.startToCloseTimeout);
    Assert.assertEquals(ACTIVITY_OPTIONS.getHeartbeatTimeout(), info.heartbeatTimeout);
    Assert.assertEquals(ActivityInfoWorkflow.class.getSimpleName(), info.workflowType);
    Assert.assertEquals(SDKTestWorkflowRule.NAMESPACE, info.namespace);
    Assert.assertEquals(testWorkflowRule.getTaskQueue(), info.activityTaskQueue);
    Assert.assertFalse(info.isLocal);
    Assert.assertEquals(0, info.priorityKey);
    // Server controls retry options so we can't make assertions what they are,
    // but they should be present
    Assert.assertTrue(info.hasRetryOptions);
  }

  @Test
  public void getLocalActivityInfo() {
    ActivityInfoWorkflow workflow = testWorkflowRule.newWorkflowStub(ActivityInfoWorkflow.class);
    SerializedActivityInfo info = workflow.getActivityInfo(true);
    // Unpredictable values
    Assert.assertFalse(info.workflowId.isEmpty());
    Assert.assertFalse(info.runId.isEmpty());
    Assert.assertFalse(info.activityId.isEmpty());
    // Predictable values
    Assert.assertEquals(0, info.taskToken.length);
    Assert.assertEquals(ActivityInfoActivity.ACTIVITY_NAME, info.activityType);
    Assert.assertEquals(
        LOCAL_ACTIVITY_OPTIONS.getScheduleToCloseTimeout(), info.scheduleToCloseTimeout);
    Assert.assertTrue(info.startToCloseTimeout.isZero());
    Assert.assertTrue(info.heartbeatTimeout.isZero());
    Assert.assertEquals(ActivityInfoWorkflow.class.getSimpleName(), info.workflowType);
    Assert.assertEquals(SDKTestWorkflowRule.NAMESPACE, info.namespace);
    Assert.assertEquals(testWorkflowRule.getTaskQueue(), info.activityTaskQueue);
    Assert.assertTrue(info.isLocal);
    Assert.assertEquals(0, info.priorityKey);
    Assert.assertTrue(info.hasRetryOptions);
    Assert.assertEquals(RETRY_OPTIONS.getInitialInterval(), info.retryInitialInterval);
    Assert.assertEquals(RETRY_OPTIONS.getBackoffCoefficient(), info.retryBackoffCoefficient, 0);
    Assert.assertEquals(RETRY_OPTIONS.getMaximumAttempts(), info.retryMaximumAttempts);
    Assert.assertEquals(RETRY_OPTIONS.getMaximumInterval(), info.retryMaximumInterval);
    Assert.assertArrayEquals(RETRY_OPTIONS.getDoNotRetry(), info.retryDoNotRetry);
  }

  @WorkflowInterface
  public interface ActivityInfoWorkflow {
    @WorkflowMethod
    SerializedActivityInfo getActivityInfo(boolean isLocal);
  }

  public static class ActivityInfoWorkflowImpl implements ActivityInfoWorkflow {
    private final ActivityInfoActivity activity =
        Workflow.newActivityStub(ActivityInfoActivity.class, ACTIVITY_OPTIONS);
    private final ActivityInfoActivity localActivity =
        Workflow.newLocalActivityStub(ActivityInfoActivity.class, LOCAL_ACTIVITY_OPTIONS);

    @Override
    public SerializedActivityInfo getActivityInfo(boolean isLocal) {
      if (isLocal) {
        return localActivity.getActivityInfo();
      } else {
        return activity.getActivityInfo();
      }
    }
  }

  @ActivityInterface
  public interface ActivityInfoActivity {
    public static final String ACTIVITY_NAME = "ActivityName_getActivityInfo";

    @ActivityMethod(name = ACTIVITY_NAME)
    SerializedActivityInfo getActivityInfo();
  }

  public static class ActivityInfoActivityImpl implements ActivityInfoActivity {
    @Override
    public SerializedActivityInfo getActivityInfo() {
      ActivityInfo info = Activity.getExecutionContext().getInfo();
      SerializedActivityInfo serialized = new SerializedActivityInfo();
      serialized.taskToken = info.getTaskToken();
      serialized.workflowId = info.getWorkflowId();
      serialized.runId = info.getRunId();
      serialized.activityId = info.getActivityId();
      serialized.activityType = info.getActivityType();
      serialized.scheduleToCloseTimeout = info.getScheduleToCloseTimeout();
      serialized.startToCloseTimeout = info.getStartToCloseTimeout();
      serialized.heartbeatTimeout = info.getHeartbeatTimeout();
      serialized.workflowType = info.getWorkflowType();
      serialized.namespace = info.getNamespace();
      serialized.activityTaskQueue = info.getActivityTaskQueue();
      serialized.isLocal = info.isLocal();
      serialized.priorityKey = info.getPriority().getPriorityKey();
      if (info.getRetryOptions() != null) {
        serialized.hasRetryOptions = true;
        serialized.retryInitialInterval = info.getRetryOptions().getInitialInterval();
        serialized.retryBackoffCoefficient = info.getRetryOptions().getBackoffCoefficient();
        serialized.retryMaximumAttempts = info.getRetryOptions().getMaximumAttempts();
        serialized.retryMaximumInterval = info.getRetryOptions().getMaximumInterval();
        if (info.getRetryOptions().getDoNotRetry() != null) {
          serialized.retryDoNotRetry = info.getRetryOptions().getDoNotRetry();
        }
      }
      return serialized;
    }
  }
}
