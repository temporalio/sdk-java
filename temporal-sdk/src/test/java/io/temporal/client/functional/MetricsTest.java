package io.temporal.client.functional;

import static io.temporal.testUtils.Eventually.assertEventually;
import static junit.framework.TestCase.*;
import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeTrue;

import com.uber.m3.tally.RootScopeBuilder;
import com.uber.m3.tally.Scope;
import io.grpc.StatusRuntimeException;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.LocalActivityOptions;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.WorkflowTaskFailedCause;
import io.temporal.api.workflowservice.v1.GetSystemInfoRequest;
import io.temporal.api.workflowservice.v1.RespondNexusTaskCompletedRequest;
import io.temporal.client.*;
import io.temporal.common.reporter.MicrometerClientStatsReporter;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.CanceledFailure;
import io.temporal.serviceclient.MetricsTag;
import io.temporal.serviceclient.MetricsType;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.*;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
          .setWorkflowTypes(
              QuicklyCompletingWorkflowImpl.class,
              MultiScenarioWorkflowImpl.class,
              UpdatableWorkflowImpl.class)
          .setActivityImplementations(runCallbackActivity, new StandaloneMetricsActivityImpl())
          .setMetricsScope(
              new RootScopeBuilder()
                  .reporter(new MicrometerClientStatsReporter(registry))
                  .reportEvery(com.uber.m3.util.Duration.ofMillis(REPORTING_FLUSH_TIME >> 1)))
          .build();

  private final ActivityClient activityClient =
      ActivityClient.newInstance(
          testWorkflowRule.getWorkflowServiceStubs(),
          ActivityClientOptions.newBuilder()
              .setNamespace(testWorkflowRule.getWorkflowClient().getOptions().getNamespace())
              .build());

  private List<Tag> tagsNamespace;
  private List<Tag> tagsNamespaceQueue;

  @Before
  public void setUp() {
    registry.clear();
    tagsNamespace =
        MetricsTag.defaultTags(testWorkflowRule.getWorkflowClient().getOptions().getNamespace())
            .entrySet()
            .stream()
            .map(
                nameValueEntry ->
                    new ImmutableTag(nameValueEntry.getKey(), nameValueEntry.getValue()))
            .collect(Collectors.toList());
    tagsNamespaceQueue =
        replaceTags(tagsNamespace, MetricsTag.TASK_QUEUE, testWorkflowRule.getTaskQueue());
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
        replaceTag(tagsNamespace, MetricsTag.OPERATION_NAME, "GetWorkflowExecutionHistory");

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
        replaceTag(tagsNamespace, MetricsTag.OPERATION_NAME, "GetWorkflowExecutionHistory");

    assertEventually(
        Duration.ofSeconds(2),
        () -> {
          assertIntCounter(1, registry.counter(MetricsType.TEMPORAL_REQUEST, startRequestTags));
          assertIntCounter(
              1, registry.counter(MetricsType.TEMPORAL_LONG_REQUEST, longPollRequestTags));
        });
  }

  @Test
  public void testNexusResponseMetricsHaveConsistentTagKeys() {
    WorkflowServiceStubs service = testWorkflowRule.getWorkflowServiceStubs();
    service.blockingStub().getSystemInfo(GetSystemInfoRequest.getDefaultInstance());

    Map<String, String> nexusTags = new HashMap<>(MetricsTag.defaultTags(MetricsTag.DEFAULT_VALUE));
    nexusTags.put(MetricsTag.NEXUS_SERVICE, "TestNexusService");
    nexusTags.put(MetricsTag.NEXUS_OPERATION, "testOperation");
    Scope nexusScope = service.getOptions().getMetricsScope().tagged(nexusTags);
    // The test server rejects the empty task token, and the interceptor records the RPC.
    assertThrows(
        StatusRuntimeException.class,
        () ->
            service
                .blockingStub()
                .withOption(MetricsTag.METRICS_TAGS_CALL_OPTIONS_KEY, nexusScope)
                .respondNexusTaskCompleted(RespondNexusTaskCompletedRequest.getDefaultInstance()));

    assertEventually(
        Duration.ofSeconds(2),
        () -> {
          for (String metric :
              new String[] {MetricsType.TEMPORAL_REQUEST, MetricsType.TEMPORAL_REQUEST_LATENCY}) {
            Meter ordinary =
                registry.find(metric).tag(MetricsTag.OPERATION_NAME, "GetSystemInfo").meter();
            Meter nexus =
                registry
                    .find(metric)
                    .tag(MetricsTag.OPERATION_NAME, "RespondNexusTaskCompleted")
                    .meter();
            assertNotNull(ordinary);
            assertNotNull(nexus);
            assertEquals(
                ordinary.getId().getTags().stream().map(Tag::getKey).collect(Collectors.toSet()),
                nexus.getId().getTags().stream().map(Tag::getKey).collect(Collectors.toSet()));
            assertEquals(
                MetricsTag.DEFAULT_VALUE, ordinary.getId().getTag(MetricsTag.NEXUS_SERVICE));
            assertEquals(
                MetricsTag.DEFAULT_VALUE, ordinary.getId().getTag(MetricsTag.NEXUS_OPERATION));
            assertEquals("TestNexusService", nexus.getId().getTag(MetricsTag.NEXUS_SERVICE));
            assertEquals("testOperation", nexus.getId().getTag(MetricsTag.NEXUS_OPERATION));
          }
        });
  }

  @Test
  public void testStandaloneActivityStartRequestTags() {
    // SAA is not supported by in-mem test server yet
    assumeTrue(SDKTestWorkflowRule.useExternalService);

    activityClient.execute(
        StandaloneMetricsActivity.class,
        StandaloneMetricsActivity::run,
        StartActivityOptions.newBuilder()
            .setId("metrics-standalone-act-" + UUID.randomUUID())
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .setScheduleToCloseTimeout(Duration.ofSeconds(10))
            .build());

    List<Tag> startActivityRequestTags =
        replaceTags(
            tagsNamespaceQueue,
            MetricsTag.OPERATION_NAME,
            "StartActivityExecution",
            MetricsTag.ACTIVITY_TYPE,
            "StandaloneMetricsActivity");

    assertEventually(
        Duration.ofSeconds(2),
        () ->
            assertIntCounter(
                1, registry.counter(MetricsType.TEMPORAL_REQUEST, startActivityRequestTags)));
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

  @Test
  public void testLongRequestMetricsHaveConsistentTagKeysForUpdates() throws Exception {
    UpdatableWorkflow workflow =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                UpdatableWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(testWorkflowRule.getTaskQueue())
                    .validateBuildWithDefaults());
    WorkflowClient.start(workflow::execute);
    assertEquals("updated", workflow.finish());
    assertEquals("done", WorkflowStub.fromTyped(workflow).getResult(String.class));

    assertEventually(
        Duration.ofSeconds(2),
        () -> {
          Set<Set<String>> tagKeySets = new HashSet<>();
          boolean sawUpdate = false;
          for (Meter meter : registry.getMeters()) {
            if (MetricsType.TEMPORAL_LONG_REQUEST_LATENCY.equals(meter.getId().getName())) {
              tagKeySets.add(
                  meter.getId().getTags().stream().map(Tag::getKey).collect(Collectors.toSet()));
              sawUpdate |=
                  "finish".equals(meter.getId().getTag(MetricsTag.UPDATE_NAME))
                      && "UpdateWorkflowExecution"
                          .equals(meter.getId().getTag(MetricsTag.OPERATION_NAME));
            }
          }
          assertTrue("no long request latency for the update was reported", sawUpdate);
          assertEquals(
              "all temporal_long_request_latency series must share the same tag keys",
              1,
              tagKeySets.size());
        });
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
  public interface UpdatableWorkflow {
    @WorkflowMethod
    String execute();

    @UpdateMethod
    String finish();
  }

  public static class UpdatableWorkflowImpl implements UpdatableWorkflow {
    private boolean finished;

    @Override
    public String execute() {
      Workflow.await(() -> finished);
      return "done";
    }

    @Override
    public String finish() {
      finished = true;
      return "updated";
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

  @ActivityInterface
  public interface StandaloneMetricsActivity {
    @ActivityMethod(name = "StandaloneMetricsActivity")
    void run();
  }

  public static class StandaloneMetricsActivityImpl implements StandaloneMetricsActivity {
    @Override
    public void run() {}
  }
}
