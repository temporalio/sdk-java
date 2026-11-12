package io.temporal.workflow.queryTests;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowException;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.internal.Issue;
import io.temporal.testing.CloudTestExclusion.NeedsCloudAdaptation;
import io.temporal.testing.CloudTestExclusionNote;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.slf4j.LoggerFactory;

/**
 * Verifies that full replays of failed workflows for queries don't spam log with misleading
 * workflow exceptions that look like original workflow execution exceptions.
 */
@Issue("https://github.com/temporalio/sdk-java/issues/1348")
public class DirectQueryReplaysDontSpamLogWithWorkflowExecutionExceptionsTest {

  private static final AtomicInteger workflowCodeExecutionCount = new AtomicInteger();
  private final ListAppender<ILoggingEvent> workflowExecuteRunnableLoggerAppender =
      new ListAppender<>();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestWorkflowNonRetryableFlag.class, LogAndKeepRunningWorkflow.class)
          .setActivityImplementations(new TestActivities.TestActivitiesImpl())
          .build();

  @Before
  public void setUp() throws Exception {
    workflowCodeExecutionCount.set(0);

    Logger workflowExecuteRunnableLogger =
        (Logger) LoggerFactory.getLogger("io.temporal.internal.sync.WorkflowExecutionHandler");
    workflowExecuteRunnableLoggerAppender.start();
    workflowExecuteRunnableLogger.addAppender(workflowExecuteRunnableLoggerAppender);
  }

  @Test
  public void queriedWorkflowFailureDoesntProduceAdditionalLogs() {
    TestWorkflows.TestWorkflowWithQuery testWorkflow =
        testWorkflowRule.newWorkflowStub(TestWorkflows.TestWorkflowWithQuery.class);
    assertThrows(WorkflowException.class, testWorkflow::execute);
    assertEquals(
        "Workflow execution exception should be logged",
        1,
        workflowExecuteRunnableLoggerAppender.list.size());
    testWorkflow.query();
    assertEquals(2, workflowCodeExecutionCount.get());
    assertEquals(
        "There was two execution - one original and one full replay for query. "
            + "But only one original exception should be logged.",
        1,
        workflowExecuteRunnableLoggerAppender.list.size());
  }

  @Test
  @CloudTestExclusionNote(
      "Uses an exact replay count that is not stable when queries run against Cloud.")
  @Category(NeedsCloudAdaptation.class)
  public void queriedWorkflowFailureDoesntProduceAdditionalLogsWhenWorkflowIsNotCompleted() {
    assumeTrue("This test is flaky on the Test Server", SDKTestWorkflowRule.useExternalService);

    QueryableWorkflowWithFailureBarrier workflow =
        testWorkflowRule.newWorkflowStub(QueryableWorkflowWithFailureBarrier.class);

    WorkflowClient.start(workflow::execute);

    // Update completion is persisted with its workflow task. A query returning the constant state
    // alone does not establish that the activity-failure-handling task has finished.
    workflow.awaitActivityFailure();
    assertEquals("my-state", workflow.getState());
    assertEquals("There was only one execution.", 1, workflowCodeExecutionCount.get());
    assertEquals(
        "The activity failure must be logged before cache eviction.",
        1,
        workflowExecuteRunnableLoggerAppender.list.size());

    testWorkflowRule.invalidateWorkflowCache();
    assertEquals("my-state", workflow.getState());
    assertEquals(
        "There was two executions - one original and one full replay for query.",
        2,
        workflowCodeExecutionCount.get());

    workflow.mySignal("exit");
    assertEquals("exit", workflow.execute());
    int executionsBeforeFinalQuery = workflowCodeExecutionCount.get();
    assertEquals("my-state", workflow.getState());
    assertEquals(
        "The final query should cause exactly one additional full replay.",
        executionsBeforeFinalQuery + 1,
        workflowCodeExecutionCount.get());
    assertEquals(
        "Only the original exception should be logged.",
        1,
        workflowExecuteRunnableLoggerAppender.list.size());
  }

  @WorkflowInterface
  public interface QueryableWorkflowWithFailureBarrier extends TestWorkflows.QueryableWorkflow {
    @UpdateMethod
    void awaitActivityFailure();
  }

  public static class LogAndKeepRunningWorkflow implements QueryableWorkflowWithFailureBarrier {
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
    private boolean activityFailureHandled;

    @Override
    public String execute() {
      workflowCodeExecutionCount.incrementAndGet();
      while (true) {
        try {
          activities.throwIO();
        } catch (ActivityFailure e) {
          logger.error("Unexpected error on activity", e);
          activityFailureHandled = true;
          Workflow.await(() -> exit);
          return "exit";
        }
      }
    }

    @Override
    public void awaitActivityFailure() {
      Workflow.await(() -> activityFailureHandled);
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

  public static class TestWorkflowNonRetryableFlag implements TestWorkflows.TestWorkflowWithQuery {

    @Override
    public String execute() {
      workflowCodeExecutionCount.incrementAndGet();
      throw ApplicationFailure.newNonRetryableFailure("SomeMessage", "SomeType");
    }

    @Override
    public String query() {
      return null;
    }
  }
}
