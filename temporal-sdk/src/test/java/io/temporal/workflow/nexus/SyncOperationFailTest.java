package io.temporal.workflow.nexus;

import static io.temporal.testing.internal.SDKTestWorkflowRule.NAMESPACE;

import com.google.common.collect.ImmutableMap;
import com.uber.m3.tally.RootScopeBuilder;
import io.nexusrpc.OperationException;
import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.client.WorkflowFailedException;
import io.temporal.common.reporter.TestStatsReporter;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.NexusOperationFailure;
import io.temporal.serviceclient.MetricsTag;
import io.temporal.testUtils.Eventually;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.MetricsType;
import io.temporal.worker.WorkerMetricsTag;
import io.temporal.workflow.*;
import io.temporal.workflow.shared.TestNexusServices;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import java.util.Map;
import org.junit.*;

public class SyncOperationFailTest {
  private final TestStatsReporter reporter = new TestStatsReporter();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestNexus.class)
          .setNexusServiceImplementation(new TestNexusServiceImpl())
          .setMetricsScope(
              new RootScopeBuilder()
                  .reporter(reporter)
                  .reportEvery(com.uber.m3.util.Duration.ofMillis(10)))
          .build();

  @Test
  public void failSyncOperation() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    WorkflowFailedException exception =
        Assert.assertThrows(WorkflowFailedException.class, () -> workflowStub.execute(""));
    Assert.assertTrue(exception.getCause() instanceof NexusOperationFailure);
    NexusOperationFailure nexusFailure = (NexusOperationFailure) exception.getCause();
    Assert.assertTrue(nexusFailure.getCause() instanceof ApplicationFailure);
    ApplicationFailure applicationFailure = (ApplicationFailure) nexusFailure.getCause();
    Assert.assertEquals("failed to call operation", applicationFailure.getOriginalMessage());
    Map<String, String> execFailedTags =
        ImmutableMap.<String, String>builder()
            .putAll(MetricsTag.defaultTags(NAMESPACE))
            .put(MetricsTag.WORKER_TYPE, WorkerMetricsTag.WorkerType.NEXUS_WORKER.getValue())
            .put(MetricsTag.TASK_QUEUE, testWorkflowRule.getTaskQueue())
            .put(MetricsTag.NEXUS_SERVICE, "TestNexusService1")
            .put(MetricsTag.NEXUS_OPERATION, "operation")
            .put(MetricsTag.TASK_FAILURE_TYPE, "operation_failed")
            .buildKeepingLast();
    Eventually.assertEventually(
        Duration.ofSeconds(1),
        () -> {
          reporter.assertCounter(MetricsType.NEXUS_EXEC_FAILED_COUNTER, execFailedTags, 4);
        });
  }

  public static class TestNexus implements TestWorkflow1 {
    @Override
    public String execute(String endpoint) {
      NexusOperationOptions options =
          NexusOperationOptions.newBuilder()
              .setScheduleToCloseTimeout(Duration.ofSeconds(5))
              .build();

      NexusServiceOptions serviceOptions =
          NexusServiceOptions.newBuilder().setOperationOptions(options).build();
      TestNexusServices.TestNexusService1 testNexusService =
          Workflow.newNexusServiceStub(TestNexusServices.TestNexusService1.class, serviceOptions);
      try {
        testNexusService.operation(Workflow.getInfo().getWorkflowId());
        Assert.fail("should not be reached");
      } catch (NexusOperationFailure nexusFailure) {
        Assert.assertTrue(nexusFailure.getCause() instanceof ApplicationFailure);
        ApplicationFailure applicationFailure = (ApplicationFailure) nexusFailure.getCause();
        Assert.assertEquals("failed to call operation", applicationFailure.getOriginalMessage());
      }

      Promise<String> failPromise =
          Async.function(testNexusService::operation, Workflow.getInfo().getWorkflowId());
      try {
        // Wait for the promise to fail
        failPromise.get();
        Assert.fail("should not be reached");
      } catch (NexusOperationFailure nexusFailure) {
        Assert.assertTrue(nexusFailure.getCause() instanceof ApplicationFailure);
        ApplicationFailure applicationFailure = (ApplicationFailure) nexusFailure.getCause();
        Assert.assertEquals("failed to call operation", applicationFailure.getOriginalMessage());
      }

      NexusOperationHandle handle =
          Workflow.startNexusOperation(
              testNexusService::operation, Workflow.getInfo().getWorkflowId());
      try {
        // Wait for the operation to fail
        handle.getExecution().get();
        Assert.fail("should not be reached");
      } catch (NexusOperationFailure nexusFailure) {
        Assert.assertTrue(nexusFailure.getCause() instanceof ApplicationFailure);
        ApplicationFailure applicationFailure = (ApplicationFailure) nexusFailure.getCause();
        Assert.assertEquals("failed to call operation", applicationFailure.getOriginalMessage());
      }
      try {
        // Since the operation has failed, the result should throw the same exception as well
        handle.getResult().get();
        Assert.fail("should not be reached");
      } catch (NexusOperationFailure nexusFailure) {
        Assert.assertTrue(nexusFailure.getCause() instanceof ApplicationFailure);
        ApplicationFailure applicationFailure = (ApplicationFailure) nexusFailure.getCause();
        Assert.assertEquals("failed to call operation", applicationFailure.getOriginalMessage());
      }
      // Throw an exception to fail the workflow and test that the exception is propagated correctly
      testNexusService.operation(Workflow.getInfo().getWorkflowId());
      // Workflow will not reach this point
      return "fail";
    }
  }

  @ServiceImpl(service = TestNexusServices.TestNexusService1.class)
  public class TestNexusServiceImpl {
    @OperationImpl
    public OperationHandler<String, String> operation() {
      // Implemented inline
      return OperationHandler.sync(
          (ctx, details, name) -> {
            throw OperationException.failure("failed to call operation");
          });
    }
  }
}
