package io.temporal.internal.worker;

import static org.junit.Assert.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.uber.m3.tally.RootScopeBuilder;
import com.uber.m3.tally.Scope;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.activity.LocalActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.reporter.TestStatsReporter;
import io.temporal.failure.ApplicationErrorCategory;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.MetricsType;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.LoggerFactory;

public class ActivityFailedMetricsTests {
  private final TestStatsReporter reporter = new TestStatsReporter();

  private static final ListAppender<ILoggingEvent> listAppender = new ListAppender<>();

  static {
    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    ch.qos.logback.classic.Logger logger = context.getLogger("io.temporal.internal.activity");
    listAppender.setContext(context);
    listAppender.start();
    logger.addAppender(listAppender);
    logger.setLevel(Level.DEBUG); // Ensure we capture both debug and warn levels
  }

  Scope metricsScope =
      new RootScopeBuilder().reporter(reporter).reportEvery(com.uber.m3.util.Duration.ofMillis(1));

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setMetricsScope(metricsScope)
          .setWorkflowTypes(ActivityWorkflowImpl.class, LocalActivityWorkflowImpl.class)
          .setActivityImplementations(new TestActivityImpl())
          .build();

  @Before
  public void setup() {
    reporter.flush();
    listAppender.list.clear();
  }

  @ActivityInterface
  public interface TestActivity {
    @ActivityMethod
    void execute(boolean isBenign);
  }

  @WorkflowInterface
  public interface ActivityWorkflow {
    @WorkflowMethod
    void execute(boolean isBenign);
  }

  @WorkflowInterface
  public interface LocalActivityWorkflow {
    @WorkflowMethod
    void execute(boolean isBenign);
  }

  public static class TestActivityImpl implements TestActivity {
    @Override
    public void execute(boolean isBenign) {
      if (!isBenign) {
        throw ApplicationFailure.newBuilder()
            .setMessage("Non-benign activity failure")
            .setType("NonBenignType")
            .build();
      } else {
        throw ApplicationFailure.newBuilder()
            .setMessage("Benign activity failure")
            .setType("BenignType")
            .setCategory(ApplicationErrorCategory.BENIGN)
            .build();
      }
    }
  }

  public static class ActivityWorkflowImpl implements ActivityWorkflow {
    @Override
    public void execute(boolean isBenign) {
      TestActivity activity =
          Workflow.newActivityStub(
              TestActivity.class,
              ActivityOptions.newBuilder()
                  .setStartToCloseTimeout(Duration.ofSeconds(3))
                  .setRetryOptions(
                      io.temporal.common.RetryOptions.newBuilder().setMaximumAttempts(1).build())
                  .build());
      activity.execute(isBenign);
    }
  }

  public static class LocalActivityWorkflowImpl implements LocalActivityWorkflow {
    @Override
    public void execute(boolean isBenign) {
      TestActivity activity =
          Workflow.newLocalActivityStub(
              TestActivity.class,
              LocalActivityOptions.newBuilder()
                  .setStartToCloseTimeout(Duration.ofSeconds(3))
                  .setRetryOptions(
                      io.temporal.common.RetryOptions.newBuilder().setMaximumAttempts(1).build())
                  .build());
      activity.execute(isBenign);
    }
  }

  private Map<String, String> getActivityTagsWithWorkerType(
      String workerType, String workflowType) {
    Map<String, String> tags = new HashMap<>();
    tags.put("task_queue", testWorkflowRule.getTaskQueue());
    tags.put("namespace", "UnitTest");
    tags.put("activity_type", "Execute");
    tags.put("exception", "ApplicationFailure");
    tags.put("worker_type", workerType);
    tags.put("workflow_type", workflowType);
    return tags;
  }

  private int countLogMessages(String message, Level level) {
    int count = 0;
    List<ILoggingEvent> list = new ArrayList<>(listAppender.list);
    for (ILoggingEvent event : list) {
      if (event.getFormattedMessage().contains(message) && event.getLevel() == level) {
        count++;
      }
    }
    return count;
  }

  @Test
  public void activityFailureMetricBenignApplicationError() {
    reporter.assertNoMetric(
        MetricsType.ACTIVITY_EXEC_FAILED_COUNTER,
        getActivityTagsWithWorkerType("ActivityWorker", "ActivityWorkflow"));

    WorkflowClient client = testWorkflowRule.getWorkflowClient();

    WorkflowFailedException nonBenignErr =
        assertThrows(
            WorkflowFailedException.class,
            () ->
                client
                    .newWorkflowStub(
                        ActivityWorkflow.class,
                        WorkflowOptions.newBuilder()
                            .setTaskQueue(testWorkflowRule.getTaskQueue())
                            .validateBuildWithDefaults())
                    .execute(false));

    assertTrue(
        "Cause should be ActivityFailure",
        nonBenignErr.getCause() instanceof io.temporal.failure.ActivityFailure);
    assertTrue(
        "Inner cause should be ApplicationFailure",
        nonBenignErr.getCause().getCause() instanceof ApplicationFailure);
    ApplicationFailure af = (ApplicationFailure) nonBenignErr.getCause().getCause();
    assertNotEquals(
        "Failure should not be benign", ApplicationErrorCategory.BENIGN, af.getCategory());
    assertEquals("Non-benign activity failure", af.getOriginalMessage());

    reporter.assertCounter(
        MetricsType.ACTIVITY_EXEC_FAILED_COUNTER,
        getActivityTagsWithWorkerType("ActivityWorker", "ActivityWorkflow"),
        1);

    // Execute workflow with benign activity failure
    WorkflowFailedException benignErr =
        assertThrows(
            WorkflowFailedException.class,
            () ->
                client
                    .newWorkflowStub(
                        ActivityWorkflow.class,
                        WorkflowOptions.newBuilder()
                            .setTaskQueue(testWorkflowRule.getTaskQueue())
                            .validateBuildWithDefaults())
                    .execute(true));

    assertTrue(
        "Cause should be ActivityFailure",
        benignErr.getCause() instanceof io.temporal.failure.ActivityFailure);
    assertTrue(
        "Inner cause should be ApplicationFailure",
        benignErr.getCause().getCause() instanceof ApplicationFailure);
    ApplicationFailure af2 = (ApplicationFailure) benignErr.getCause().getCause();
    assertEquals("Failure should be benign", ApplicationErrorCategory.BENIGN, af2.getCategory());
    assertEquals("Benign activity failure", af2.getOriginalMessage());

    // Expect metrics to remain unchanged for benign failure
    reporter.assertCounter(
        MetricsType.ACTIVITY_EXEC_FAILED_COUNTER,
        getActivityTagsWithWorkerType("ActivityWorker", "ActivityWorkflow"),
        1);

    // Verify log levels
    assertEquals(1, countLogMessages("Activity failure.", Level.WARN));
    assertEquals(1, countLogMessages("Activity failure.", Level.DEBUG));
  }

  @Test
  public void localActivityFailureMetricBenignApplicationError() {
    reporter.assertNoMetric(
        MetricsType.LOCAL_ACTIVITY_EXEC_FAILED_COUNTER,
        getActivityTagsWithWorkerType("LocalActivityWorker", "LocalActivityWorkflow"));

    WorkflowClient client = testWorkflowRule.getWorkflowClient();

    WorkflowFailedException nonBenignErr =
        assertThrows(
            WorkflowFailedException.class,
            () ->
                client
                    .newWorkflowStub(
                        LocalActivityWorkflow.class,
                        WorkflowOptions.newBuilder()
                            .setTaskQueue(testWorkflowRule.getTaskQueue())
                            .validateBuildWithDefaults())
                    .execute(false));

    assertTrue(
        "Cause should be ActivityFailure",
        nonBenignErr.getCause() instanceof io.temporal.failure.ActivityFailure);
    assertTrue(
        "Inner cause should be ApplicationFailure",
        nonBenignErr.getCause().getCause() instanceof ApplicationFailure);
    ApplicationFailure af = (ApplicationFailure) nonBenignErr.getCause().getCause();
    assertNotEquals(
        "Failure should not be benign", ApplicationErrorCategory.BENIGN, af.getCategory());
    assertEquals("Non-benign activity failure", af.getOriginalMessage());

    // Expect metrics to be incremented for non-benign failure
    reporter.assertCounter(
        MetricsType.LOCAL_ACTIVITY_EXEC_FAILED_COUNTER,
        getActivityTagsWithWorkerType("LocalActivityWorker", "LocalActivityWorkflow"),
        1);

    WorkflowFailedException benignErr =
        assertThrows(
            WorkflowFailedException.class,
            () ->
                client
                    .newWorkflowStub(
                        LocalActivityWorkflow.class,
                        WorkflowOptions.newBuilder()
                            .setTaskQueue(testWorkflowRule.getTaskQueue())
                            .validateBuildWithDefaults())
                    .execute(true));

    assertTrue(
        "Cause should be ActivityFailure",
        benignErr.getCause() instanceof io.temporal.failure.ActivityFailure);
    assertTrue(
        "Inner cause should be ApplicationFailure",
        benignErr.getCause().getCause() instanceof ApplicationFailure);
    ApplicationFailure af2 = (ApplicationFailure) benignErr.getCause().getCause();
    assertEquals("Failure should be benign", ApplicationErrorCategory.BENIGN, af2.getCategory());
    assertEquals("Benign activity failure", af2.getOriginalMessage());

    // Expect metrics to remain unchanged for benign failure
    reporter.assertCounter(
        MetricsType.LOCAL_ACTIVITY_EXEC_FAILED_COUNTER,
        getActivityTagsWithWorkerType("LocalActivityWorker", "LocalActivityWorkflow"),
        1);

    // Verify log levels
    assertEquals(1, countLogMessages("Local activity failure.", Level.WARN));
    assertEquals(1, countLogMessages("Local activity failure.", Level.DEBUG));
  }
}
