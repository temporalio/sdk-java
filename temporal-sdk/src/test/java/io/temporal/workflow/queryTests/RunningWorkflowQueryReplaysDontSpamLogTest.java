package io.temporal.workflow.queryTests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.internal.Issue;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.LoggerFactory;

/**
 * Same guarantee as {@link DirectQueryReplaysDontSpamLogWithWorkflowExecutionExceptionsTest}, but
 * for a workflow that is still running.
 *
 * <p>This lives in its own class because {@code workflowCodeExecutionCount} and the log appender
 * are shared per class. Sharing them with a test whose workflow fails lets that workflow's trailing
 * replay land in this test's counter and appender.
 */
@Issue("https://github.com/temporalio/sdk-java/issues/1348")
public class RunningWorkflowQueryReplaysDontSpamLogTest {

  private static final AtomicInteger workflowCodeExecutionCount = new AtomicInteger();
  private final ListAppender<ILoggingEvent> workflowExecuteRunnableLoggerAppender =
      new ListAppender<>();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(LogAndKeepRunningWorkflow.class)
          .setActivityImplementations(new TestActivities.TestActivitiesImpl())
          .build();

  @Before
  public void setUp() {
    workflowCodeExecutionCount.set(0);

    Logger workflowExecuteRunnableLogger =
        (Logger) LoggerFactory.getLogger("io.temporal.internal.sync.WorkflowExecutionHandler");
    workflowExecuteRunnableLoggerAppender.start();
    workflowExecuteRunnableLogger.addAppender(workflowExecuteRunnableLoggerAppender);
  }

  @Test
  public void queriedWorkflowFailureDoesntProduceAdditionalLogsWhenWorkflowIsNotCompleted() {
    assumeTrue("This test is flaky on the Test Server", SDKTestWorkflowRule.useExternalService);

    TestWorkflows.QueryableWorkflow workflow =
        testWorkflowRule.newWorkflowStub(TestWorkflows.QueryableWorkflow.class);

    WorkflowClient.start(workflow::execute);

    assertEquals("my-state", workflow.getState());
    assertEquals("There was only one execution.", 1, workflowCodeExecutionCount.get());

    testWorkflowRule.invalidateWorkflowCache();
    assertEquals("my-state", workflow.getState());
    assertEquals(
        "There was two executions - one original and one full replay for query.",
        2,
        workflowCodeExecutionCount.get());

    workflow.mySignal("exit");
    assertEquals("exit", workflow.execute());
    assertEquals("my-state", workflow.getState());
    assertEquals(
        "There was three executions - one original and two full replays for query.",
        3,
        workflowCodeExecutionCount.get());
    assertEquals(
        "Only the original exception should be logged.",
        1,
        workflowExecuteRunnableLoggerAppender.list.size());
  }

  public static class LogAndKeepRunningWorkflow implements TestWorkflows.QueryableWorkflow {
    private final org.slf4j.Logger logger =
        Workflow.getLogger("io.temporal.internal.sync.WorkflowExecutionHandler");
    private final TestActivities.VariousTestActivities activities =
        Workflow.newActivityStub(
            TestActivities.VariousTestActivities.class,
            ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(10))
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                .build());
    private boolean exit;

    @Override
    public String execute() {
      workflowCodeExecutionCount.incrementAndGet();
      while (true) {
        try {
          activities.throwIO();
        } catch (ActivityFailure e) {
          logger.error("Unexpected error on activity", e);
          Workflow.await(() -> exit);
          return "exit";
        }
      }
    }

    @Override
    public String getState() {
      return "my-state";
    }

    @Override
    public void mySignal(String value) {
      exit = true;
    }
  }
}
