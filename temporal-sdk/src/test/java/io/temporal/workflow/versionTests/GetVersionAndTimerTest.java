package io.temporal.workflow.versionTests;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

import io.temporal.client.WorkflowOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.time.Instant;
import org.junit.Rule;
import org.junit.Test;

public class GetVersionAndTimerTest extends BaseVersionTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRuleWithoutVersion =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              getDefaultWorkflowImplementationOptions(), TimedWorkflowWithoutVersionImpl.class)
          .build();

  @Rule
  public SDKTestWorkflowRule testWorkflowRuleWithVersion =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(TimedWorkflowWithVersionImpl.class).build();

  public GetVersionAndTimerTest(boolean setVersioningFlag, boolean upsertVersioningSA) {
    super(setVersioningFlag, upsertVersioningSA);
  }

  @Test
  public void testTimedWorkflowWithoutVersionImpl() {
    assumeFalse("skipping for docker tests", SDKTestWorkflowRule.useExternalService);
    testTimedWorkflow(testWorkflowRuleWithoutVersion);
  }

  @Test
  public void testTimedWorkflowWithVersionImpl() {
    assumeFalse("skipping for docker tests", SDKTestWorkflowRule.useExternalService);
    testTimedWorkflow(testWorkflowRuleWithVersion);
  }

  private void testTimedWorkflow(SDKTestWorkflowRule rule) {
    WorkflowOptions workflowOptions =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofDays(1))
            .setWorkflowTaskTimeout(Duration.ofSeconds(5))
            .setTaskQueue(rule.getTaskQueue())
            .build();
    TimedWorkflow workflowStub =
        rule.getWorkflowClient().newWorkflowStub(TimedWorkflow.class, workflowOptions);

    Instant startInstant = Instant.ofEpochMilli(rule.getTestEnvironment().currentTimeMillis());

    Instant endInstant = workflowStub.startAndWait();

    assertTrue(
        "endInstant "
            + endInstant
            + " should be more than 2 hours away from startInstant "
            + startInstant,
        endInstant.isAfter(startInstant.plus(Duration.ofHours(2))));
  }

  @WorkflowInterface
  public interface TimedWorkflow {

    @WorkflowMethod
    Instant startAndWait();
  }

  abstract static class TimedWorkflowImpl implements TimedWorkflow {
    @Override
    public Instant startAndWait() {
      getVersion();

      Workflow.newTimer(Duration.ofMinutes(1))
          .thenApply(
              (v) -> {
                getVersion();
                return v;
              });

      Workflow.sleep(Duration.ofHours(2));

      return Instant.ofEpochMilli(Workflow.currentTimeMillis());
    }

    protected abstract void getVersion();
  }

  public static class TimedWorkflowWithoutVersionImpl extends TimedWorkflowImpl
      implements TimedWorkflow {

    @Override
    protected void getVersion() {
      // Do nothing
    }
  }

  public static class TimedWorkflowWithVersionImpl extends TimedWorkflowImpl
      implements TimedWorkflow {

    @Override
    protected void getVersion() {
      Workflow.getVersion("id", Workflow.DEFAULT_VERSION, 1);
    }
  }
}
