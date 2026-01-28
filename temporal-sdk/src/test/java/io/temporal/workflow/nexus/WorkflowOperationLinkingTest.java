package io.temporal.workflow.nexus;

import static io.temporal.internal.common.WorkflowExecutionUtils.getEventOfType;

import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.History;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.WorkflowExecutionStartedEventAttributes;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.internal.nexus.OperationTokenUtil;
import io.temporal.nexus.Nexus;
import io.temporal.nexus.WorkflowRunOperation;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.*;
import io.temporal.workflow.shared.TestNexusServices;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class WorkflowOperationLinkingTest extends BaseNexusTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestNexus.class, TestOperationWorkflow.class)
          .setNexusServiceImplementation(new TestNexusServiceImpl())
          .build();

  @Override
  protected SDKTestWorkflowRule getTestWorkflowRule() {
    return testWorkflowRule;
  }

  @Test
  public void testWorkflowOperationLinks() {
    TestWorkflows.TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.TestWorkflow1.class);
    String result = workflowStub.execute(testWorkflowRule.getTaskQueue());
    Assert.assertEquals("Hello from operation workflow " + testWorkflowRule.getTaskQueue(), result);
    String originalWorkflowId = WorkflowStub.fromTyped(workflowStub).getExecution().getWorkflowId();
    History history =
        testWorkflowRule.getWorkflowClient().fetchHistory(originalWorkflowId).getHistory();
    // Assert that the operation started event has a link to the workflow execution started event
    HistoryEvent nexusStartedEvent =
        getEventOfType(history, EventType.EVENT_TYPE_NEXUS_OPERATION_STARTED);
    Assert.assertEquals(1, nexusStartedEvent.getLinksCount());
    Assert.assertEquals(
        EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED,
        nexusStartedEvent.getLinks(0).getWorkflowEvent().getEventRef().getEventType());
    // Assert that the started workflow has a link to the caller workflow
    History linkedHistory =
        testWorkflowRule
            .getWorkflowClient()
            .fetchHistory(nexusStartedEvent.getLinks(0).getWorkflowEvent().getWorkflowId())
            .getHistory();
    HistoryEvent linkedStartedEvent = linkedHistory.getEventsList().get(0);
    WorkflowExecutionStartedEventAttributes attrs =
        linkedStartedEvent.getWorkflowExecutionStartedEventAttributes();
    // Nexus links in callbacks are deduped
    Assert.assertEquals(0, linkedStartedEvent.getLinksCount());
    Assert.assertEquals(1, attrs.getCompletionCallbacksCount());
    Assert.assertEquals(
        originalWorkflowId,
        attrs.getCompletionCallbacks(0).getLinks(0).getWorkflowEvent().getWorkflowId());
  }

  public static class TestNexus implements TestWorkflows.TestWorkflow1 {
    @Override
    public String execute(String input) {
      NexusOperationOptions options =
          NexusOperationOptions.newBuilder()
              .setScheduleToCloseTimeout(Duration.ofSeconds(10))
              .build();
      NexusServiceOptions serviceOptions =
          NexusServiceOptions.newBuilder()
              .setEndpoint(getEndpointName())
              .setOperationOptions(options)
              .build();
      TestNexusServices.TestNexusService1 serviceStub =
          Workflow.newNexusServiceStub(TestNexusServices.TestNexusService1.class, serviceOptions);
      // Start an asynchronous operation backed by a workflow
      NexusOperationHandle<String> asyncOpHandle =
          Workflow.startNexusOperation(serviceStub::operation, input);
      NexusOperationExecution asyncExec = asyncOpHandle.getExecution().get();
      // Signal the operation to unblock, this makes sure the operation doesn't complete before the
      // operation
      // started event is written to history
      Workflow.newExternalWorkflowStub(
              OperationWorkflow.class,
              OperationTokenUtil.loadWorkflowRunOperationToken(asyncExec.getOperationToken().get())
                  .getWorkflowId())
          .unblock();
      return asyncOpHandle.getResult().get();
    }
  }

  @WorkflowInterface
  public interface OperationWorkflow {
    @WorkflowMethod
    String execute(String arg);

    @SignalMethod
    void unblock();
  }

  public static class TestOperationWorkflow implements OperationWorkflow {
    boolean unblocked = false;

    @Override
    public String execute(String arg) {
      Workflow.await(() -> unblocked);
      return "Hello from operation workflow " + arg;
    }

    @Override
    public void unblock() {
      unblocked = true;
    }
  }

  @ServiceImpl(service = TestNexusServices.TestNexusService1.class)
  public class TestNexusServiceImpl {
    @OperationImpl
    public OperationHandler<String, String> operation() {
      return WorkflowRunOperation.fromWorkflowMethod(
          (context, details, input) ->
              Nexus.getOperationContext()
                      .getWorkflowClient()
                      .newWorkflowStub(
                          AsyncWorkflowOperationTest.OperationWorkflow.class,
                          WorkflowOptions.newBuilder()
                              .setWorkflowId(details.getRequestId())
                              .build())
                  ::execute);
    }
  }
}
