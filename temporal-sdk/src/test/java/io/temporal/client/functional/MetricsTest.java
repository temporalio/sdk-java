package io.temporal.client.functional;

import static io.temporal.testUtils.Eventually.assertEventually;
import static io.temporal.testing.internal.SDKTestWorkflowRule.NAMESPACE;
import static junit.framework.TestCase.*;

import com.uber.m3.tally.RootScopeBuilder;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.LocalActivityOptions;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.WorkflowTaskFailedCause;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.reporter.MicrometerClientStatsReporter;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.CanceledFailure;
import io.temporal.serviceclient.MetricsTag;
import io.temporal.serviceclient.MetricsType;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.*;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class MetricsTest {

  private static final long REPORTING_FLUSH_TIME = 50;

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final RunCallbackActivityImpl runCallbackActivity = new RunCallbackActivityImpl();

  @Rule
  public final SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(QuicklyCompletingWorkflowImpl.class, MultiScenarioWorkflowImpl.class)
          .setActivityImplementations(runCallbackActivity)
          .setMetricsScope(
              new RootScopeBuilder()
                  .reporter(new MicrometerClientStatsReporter(registry))
                  .reportEvery(com.uber.m3.util.Duration.ofMillis(REPORTING_FLUSH_TIME >> 1)))
          .build();

  private static final List<Tag> TAGS_NAMESPACE =
      MetricsTag.defaultTags(NAMESPACE).entrySet().stream()
          .map(
              nameValueEntry ->
                  new ImmutableTag(nameValueEntry.getKey(), nameValueEntry.getValue()))
          .collect(Collectors.toList());

  private List<Tag> tagsNamespaceQueue;

  @Before
  public void setUp() {
    registry.clear();
    tagsNamespaceQueue =
        replaceTags(TAGS_NAMESPACE, MetricsTag.TASK_QUEUE, testWorkflowRule.getTaskQueue());
  }

  @After
  public void tearDown() {
    this.registry.close();
  }

  @Test
  public void testSynchronousStartAndGetResult() throws InterruptedException {
    QuicklyCompletingWorkflow quicklyCompletingWorkflow =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                QuicklyCompletingWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(testWorkflowRule.getTaskQueue())
                    .validateBuildWithDefaults());
    quicklyCompletingWorkflow.execute();

    List<Tag> startRequestTags =
        replaceTags(
            tagsNamespaceQueue,
            MetricsTag.OPERATION_NAME,
            "StartWorkflowExecution",
            MetricsTag.WORKFLOW_TYPE,
            "QuicklyCompletingWorkflow");
    List<Tag> longPollRequestTags =
        replaceTag(TAGS_NAMESPACE, MetricsTag.OPERATION_NAME, "GetWorkflowExecutionHistory");

    assertEventually(
        Duration.ofSeconds(2),
        () -> {
          assertIntCounter(
              1, registry.counter(MetricsType.TEMPORAL_LONG_REQUEST, longPollRequestTags));
          assertIntCounter(1, registry.counter(MetricsType.TEMPORAL_REQUEST, startRequestTags));
        });
  }

  @Test
  public void testAsynchronousStartAndGetResult() throws InterruptedException, ExecutionException {
    QuicklyCompletingWorkflow quicklyCompletingWorkflow =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                QuicklyCompletingWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(testWorkflowRule.getTaskQueue())
                    .validateBuildWithDefaults());
    WorkflowStub workflowStub = WorkflowStub.fromTyped(quicklyCompletingWorkflow);
    workflowStub.start();
    workflowStub.getResultAsync(String.class).get();

    List<Tag> startRequestTags =
        replaceTags(
            tagsNamespaceQueue,
            MetricsTag.OPERATION_NAME,
            "StartWorkflowExecution",
            MetricsTag.WORKFLOW_TYPE,
            "QuicklyCompletingWorkflow");
    List<Tag> longPollRequestTags =
        replaceTag(TAGS_NAMESPACE, MetricsTag.OPERATION_NAME, "GetWorkflowExecutionHistory");

    assertEventually(
        Duration.ofSeconds(2),
        () -> {
          assertIntCounter(1, registry.counter(MetricsType.TEMPORAL_REQUEST, startRequestTags));
          assertIntCounter(
              1, registry.counter(MetricsType.TEMPORAL_LONG_REQUEST, longPollRequestTags));
        });
  }

  @Test
  public void testWorkflowSuccess() {
    String result =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                MultiScenarioWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(testWorkflowRule.getTaskQueue())
                    .validateBuildWithDefaults())
            .execute(MultiScenarioWorkflow.Scenario.SUCCESS);
    assertEquals("success", result);
    assertSingleMeterCountForMultiScenario(
        io.temporal.worker.MetricsType.WORKFLOW_COMPLETED_COUNTER);
    assertSingleMeterCountForMultiScenario(io.temporal.worker.MetricsType.WORKFLOW_E2E_LATENCY);
  }

  @Test
  public void testWorkflowFailure() {
    try {
      testWorkflowRule
          .getWorkflowClient()
          .newWorkflowStub(
              MultiScenarioWorkflow.class,
              WorkflowOptions.newBuilder()
                  .setTaskQueue(testWorkflowRule.getTaskQueue())
                  .validateBuildWithDefaults())
          .execute(MultiScenarioWorkflow.Scenario.FAILURE);
      fail();
    } catch (WorkflowFailedException e) {
      assertTrue(e.getCause() instanceof ApplicationFailure);
    }
    assertSingleMeterCountForMultiScenario(io.temporal.worker.MetricsType.WORKFLOW_FAILED_COUNTER);
    assertSingleMeterCountForMultiScenario(io.temporal.worker.MetricsType.WORKFLOW_E2E_LATENCY);
  }

  @Test
  public void testWorkflowCancel() {
    WorkflowStub stub =
        WorkflowStub.fromTyped(
            testWorkflowRule
                .getWorkflowClient()
                .newWorkflowStub(
                    MultiScenarioWorkflow.class,
                    WorkflowOptions.newBuilder()
                        .setTaskQueue(testWorkflowRule.getTaskQueue())
                        .validateBuildWithDefaults()));
    WorkflowExecution exec = stub.start(MultiScenarioWorkflow.Scenario.WAIT_FOR_CANCEL);
    testWorkflowRule.getWorkflowClient().newUntypedWorkflowStub(exec.getWorkflowId()).cancel();
    try {
      stub.getResult(String.class);
      fail();
    } catch (WorkflowFailedException e) {
      assertTrue(e.getCause() instanceof CanceledFailure);
    }
    assertSingleMeterCountForMultiScenario(
        io.temporal.worker.MetricsType.WORKFLOW_CANCELED_COUNTER);
    assertSingleMeterCountForMultiScenario(io.temporal.worker.MetricsType.WORKFLOW_E2E_LATENCY);
  }

  @Test
  public void testWorkflowContinueAsNew() {
    String result =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                MultiScenarioWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(testWorkflowRule.getTaskQueue())
                    .validateBuildWithDefaults())
            .execute(MultiScenarioWorkflow.Scenario.CONTINUE_AS_NEW);
    assertEquals("success", result);
    assertSingleMeterCountForMultiScenario(
        io.temporal.worker.MetricsType.WORKFLOW_CONTINUE_AS_NEW_COUNTER);
    assertSingleMeterCountForMultiScenario(
        io.temporal.worker.MetricsType.WORKFLOW_COMPLETED_COUNTER);
    // We cannot reliably check e2e latency here because it compares current ms with event start ms,
    // and event start ms for the second workflow of a continue as new when time skipping can be
    // flaky
  }

  @Test
  public void testUnhandledCommand() throws Exception {
    // We're going to have a local activity send a signal to cause unhandled command
    String workflowId = UUID.randomUUID().toString();
    runCallbackActivity.callbackOnNextRun.set(
        () ->
            testWorkflowRule
                .getWorkflowClient()
                .newUntypedWorkflowStub(workflowId)
                .signal("some-signal"));

    // Run the workflow and confirm success
    String result =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                MultiScenarioWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(testWorkflowRule.getTaskQueue())
                    .setWorkflowId(workflowId)
                    .validateBuildWithDefaults())
            .execute(MultiScenarioWorkflow.Scenario.UNHANDLED_COMMAND);
    assertEquals("success", result);

    // Confirm unhandled command in history
    assertTrue(
        testWorkflowRule
            .getWorkflowClient()
            .streamHistory(workflowId)
            .anyMatch(
                evt ->
                    evt.getWorkflowTaskFailedEventAttributes().getCause()
                        == WorkflowTaskFailedCause.WORKFLOW_TASK_FAILED_CAUSE_UNHANDLED_COMMAND));

    // Confirm we only got the one workflow completed. Before the code fixes that were added with
    // this test, this would have returned multiple workflow completed counts.
    assertSingleMeterCountForMultiScenario(
        io.temporal.worker.MetricsType.WORKFLOW_COMPLETED_COUNTER);
  }

  private void assertSingleMeterCountForMultiScenario(String metric) {
    assertEventually(
        Duration.ofSeconds(2),
        () -> {
          AtomicInteger compCount = new AtomicInteger();
          registry.forEachMeter(
              meter -> {
                if (metric.equals(meter.getId().getName())
                    && "MultiScenarioWorkflow".equals(meter.getId().getTag("workflow_type"))) {
                  if (meter instanceof Counter) {
                    compCount.addAndGet((int) ((Counter) meter).count());
                  } else if (meter instanceof Timer) {
                    compCount.addAndGet((int) ((Timer) meter).count());
                  }
                }
              });
          assertEquals(1, compCount.get());
        });
  }

  private static List<Tag> replaceTags(List<Tag> tags, String... nameValuePairs) {
    for (int i = 0; i < nameValuePairs.length; i += 2) {
      tags = replaceTag(tags, nameValuePairs[i], nameValuePairs[i + 1]);
    }
    return tags;
  }

  private static List<Tag> replaceTag(List<Tag> tags, String name, String value) {
    List<Tag> result =
        tags.stream().filter(tag -> !name.equals(tag.getKey())).collect(Collectors.toList());
    result.add(new ImmutableTag(name, value));
    return result;
  }

  private void assertIntCounter(int expectedValue, Counter counter) {
    assertEquals(expectedValue, Math.round(counter.count()));
  }

  @WorkflowInterface
  public interface QuicklyCompletingWorkflow {
    @WorkflowMethod
    String execute();
  }

  public static class QuicklyCompletingWorkflowImpl implements QuicklyCompletingWorkflow {

    @Override
    public String execute() {
      return "done";
    }
  }

  @WorkflowInterface
  public interface MultiScenarioWorkflow {
    enum Scenario {
      SUCCESS,
      FAILURE,
      WAIT_FOR_CANCEL,
      CONTINUE_AS_NEW,
      UNHANDLED_COMMAND
    }

    @WorkflowMethod
    String execute(Scenario scenario);
  }

  public static class MultiScenarioWorkflowImpl implements MultiScenarioWorkflow {
    @Override
    public String execute(Scenario scenario) {
      switch (scenario) {
        case SUCCESS:
          return "success";
        case FAILURE:
          throw ApplicationFailure.newFailure("Intentional failure", "failure");
        case WAIT_FOR_CANCEL:
          Workflow.await(() -> false);
          throw new IllegalStateException("Unreachable");
        case CONTINUE_AS_NEW:
          Workflow.continueAsNew(Scenario.SUCCESS);
          throw new IllegalStateException("Unreachable");
        case UNHANDLED_COMMAND:
          Workflow.newLocalActivityStub(
                  RunCallbackActivity.class,
                  LocalActivityOptions.newBuilder()
                      .setScheduleToCloseTimeout(Duration.ofSeconds(5))
                      .build())
              .runCallback();
          return "success";
        default:
          throw new UnsupportedOperationException();
      }
    }
  }

  @ActivityInterface
  public interface RunCallbackActivity {
    @ActivityMethod
    void runCallback();
  }

  public static class RunCallbackActivityImpl implements RunCallbackActivity {
    private final AtomicReference<Runnable> callbackOnNextRun = new AtomicReference<>();

    @Override
    public void runCallback() {
      Runnable toRun = callbackOnNextRun.getAndSet(null);
      if (toRun != null) {
        toRun.run();
      }
    }
  }
}
