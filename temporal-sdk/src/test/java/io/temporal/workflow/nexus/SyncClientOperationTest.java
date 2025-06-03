package io.temporal.workflow.nexus;

import static io.temporal.testing.internal.SDKTestWorkflowRule.NAMESPACE;

import com.google.common.collect.ImmutableMap;
import com.uber.m3.tally.RootScopeBuilder;
import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.client.WorkflowFailedException;
import io.temporal.common.reporter.TestStatsReporter;
import io.temporal.failure.ApplicationFailure;
import io.temporal.nexus.Nexus;
import io.temporal.serviceclient.MetricsTag;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.testing.internal.TracingWorkerInterceptor;
import io.temporal.worker.MetricsType;
import io.temporal.worker.WorkerMetricsTag;
import io.temporal.workflow.*;
import io.temporal.workflow.shared.TestNexusServices;
import java.time.Duration;
import java.util.Map;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class SyncClientOperationTest {
  private final TestStatsReporter reporter = new TestStatsReporter();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestNexus.class)
          .setMetricsScope(
              new RootScopeBuilder()
                  .reporter(reporter)
                  .reportEvery(com.uber.m3.util.Duration.ofMillis(10)))
          .setNexusServiceImplementation(new TestNexusServiceImpl())
          .build();

  @Test
  public void syncClientOperationSuccess() {
    TestUpdatedWorkflow workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestUpdatedWorkflow.class);
    Assert.assertTrue(workflowStub.execute(false).startsWith("Update ID:"));
    testWorkflowRule
        .getInterceptor(TracingWorkerInterceptor.class)
        .setExpected(
            "interceptExecuteWorkflow " + SDKTestWorkflowRule.UUID_REGEXP,
            "registerUpdateHandlers update",
            "newThread workflow-method",
            "executeNexusOperation TestNexusService1 operation",
            "startNexusOperation TestNexusService1 operation");
    // Test metrics all tasks should have
    Map<String, String> nexusWorkerTags =
        ImmutableMap.<String, String>builder()
            .putAll(MetricsTag.defaultTags(NAMESPACE))
            .put(MetricsTag.WORKER_TYPE, WorkerMetricsTag.WorkerType.NEXUS_WORKER.getValue())
            .put(MetricsTag.TASK_QUEUE, testWorkflowRule.getTaskQueue())
            .buildKeepingLast();
    reporter.assertTimer(MetricsType.NEXUS_SCHEDULE_TO_START_LATENCY, nexusWorkerTags);
    Map<String, String> operationTags =
        ImmutableMap.<String, String>builder()
            .putAll(nexusWorkerTags)
            .put(MetricsTag.NEXUS_SERVICE, "TestNexusService1")
            .put(MetricsTag.NEXUS_OPERATION, "operation")
            .buildKeepingLast();
    reporter.assertTimer(MetricsType.NEXUS_EXEC_LATENCY, operationTags);
    reporter.assertTimer(MetricsType.NEXUS_TASK_E2E_LATENCY, operationTags);
    // Test our custom metric
    reporter.assertCounter("operation", operationTags, 1);
  }

  @Test
  public void syncClientOperationFail() {
    TestUpdatedWorkflow workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestUpdatedWorkflow.class);
    Assert.assertThrows(WorkflowFailedException.class, () -> workflowStub.execute(true));

    // Test metrics all failed tasks should have
    Map<String, String> nexusWorkerTags =
        ImmutableMap.<String, String>builder()
            .putAll(MetricsTag.defaultTags(NAMESPACE))
            .put(MetricsTag.WORKER_TYPE, WorkerMetricsTag.WorkerType.NEXUS_WORKER.getValue())
            .put(MetricsTag.TASK_QUEUE, testWorkflowRule.getTaskQueue())
            .buildKeepingLast();
    reporter.assertTimer(MetricsType.NEXUS_SCHEDULE_TO_START_LATENCY, nexusWorkerTags);
    Map<String, String> operationTags =
        ImmutableMap.<String, String>builder()
            .putAll(nexusWorkerTags)
            .put(MetricsTag.NEXUS_SERVICE, "TestNexusService1")
            .put(MetricsTag.NEXUS_OPERATION, "operation")
            .buildKeepingLast();
    reporter.assertTimer(MetricsType.NEXUS_EXEC_LATENCY, operationTags);
    reporter.assertTimer(MetricsType.NEXUS_TASK_E2E_LATENCY, operationTags);
    Map<String, String> execFailedTags =
        ImmutableMap.<String, String>builder()
            .putAll(operationTags)
            .put(MetricsTag.TASK_FAILURE_TYPE, "handler_error_INTERNAL")
            .buildKeepingLast();
    reporter.assertCounter(MetricsType.NEXUS_EXEC_FAILED_COUNTER, execFailedTags, 1);
  }

  @WorkflowInterface
  public interface TestUpdatedWorkflow {

    @WorkflowMethod
    String execute(boolean fail);

    @UpdateMethod
    String update(String arg);
  }

  public static class TestNexus implements TestUpdatedWorkflow {
    @Override
    public String execute(boolean fail) {
      NexusOperationOptions options =
          NexusOperationOptions.newBuilder()
              .setScheduleToCloseTimeout(Duration.ofSeconds(1))
              .build();
      NexusServiceOptions serviceOptions =
          NexusServiceOptions.newBuilder().setOperationOptions(options).build();
      // Try to call a synchronous operation in a blocking way
      TestNexusServices.TestNexusService1 serviceStub =
          Workflow.newNexusServiceStub(TestNexusServices.TestNexusService1.class, serviceOptions);
      return serviceStub.operation(fail ? "" : Workflow.getInfo().getWorkflowId());
    }

    @Override
    public String update(String arg) {
      return "Update ID: " + Workflow.getCurrentUpdateInfo().get().getUpdateId();
    }
  }

  @ServiceImpl(service = TestNexusServices.TestNexusService1.class)
  public class TestNexusServiceImpl {
    @OperationImpl
    public OperationHandler<String, String> operation() {
      // Implemented inline
      return OperationHandler.sync(
          (ctx, details, id) -> {
            if (id.isEmpty()) {
              throw ApplicationFailure.newNonRetryableFailure("Invalid ID", "TestError");
            }
            Nexus.getOperationContext().getMetricsScope().counter("operation").inc(1);
            return Nexus.getOperationContext()
                .getWorkflowClient()
                .newWorkflowStub(TestUpdatedWorkflow.class, id)
                .update("Update from operation");
          });
    }
  }
}
