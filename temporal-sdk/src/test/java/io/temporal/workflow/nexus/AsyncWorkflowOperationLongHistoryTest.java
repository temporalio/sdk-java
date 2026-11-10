package io.temporal.workflow.nexus;

import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.nexus.Nexus;
import io.temporal.nexus.WorkflowRunOperation;
import io.temporal.testing.CloudTestExclusion.RequiresCloudProvisioning;
import io.temporal.testing.CloudTestExclusionNote;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.*;
import io.temporal.workflow.shared.TestNexusServices;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Verifies that an async operation completes when its scheduled event ID is above 127, so that its
 * binary form contains bytes that are not valid UTF-8 on their own.
 */
@CloudTestExclusionNote("Cloud CI does not provision the Nexus endpoint required by this test.")
@Category(RequiresCloudProvisioning.class)
public class AsyncWorkflowOperationLongHistoryTest {
  // Each side effect records one marker event, which pushes the NEXUS_OPERATION_SCHEDULED event ID
  // into the 128-255 range.
  private static final int SIDE_EFFECT_COUNT = 150;

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestNexus.class, TestOperationWorkflow.class)
          .setNexusServiceImplementation(new TestNexusServiceImpl())
          .build();

  @Test
  public void asyncOperationScheduledAfterEventId127Completes() {
    TestWorkflows.TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.TestWorkflow1.class);
    String result = workflowStub.execute(testWorkflowRule.getTaskQueue());
    Assert.assertEquals("Hello from operation workflow " + testWorkflowRule.getTaskQueue(), result);

    String workflowId = WorkflowStub.fromTyped(workflowStub).getExecution().getWorkflowId();
    HistoryEvent scheduled =
        testWorkflowRule.getHistoryEvent(
            workflowId, EventType.EVENT_TYPE_NEXUS_OPERATION_SCHEDULED);
    Assert.assertTrue(
        "Scheduled event ID should be above 127, was " + scheduled.getEventId(),
        scheduled.getEventId() > 127);
  }

  public static class TestNexus implements TestWorkflows.TestWorkflow1 {
    @Override
    public String execute(String input) {
      for (int i = 0; i < SIDE_EFFECT_COUNT; i++) {
        Workflow.sideEffect(Integer.class, () -> 0);
      }
      NexusOperationOptions options =
          NexusOperationOptions.newBuilder()
              .setScheduleToCloseTimeout(Duration.ofSeconds(10))
              .build();
      NexusServiceOptions serviceOptions =
          NexusServiceOptions.newBuilder().setOperationOptions(options).build();
      TestNexusServices.TestNexusService1 serviceStub =
          Workflow.newNexusServiceStub(TestNexusServices.TestNexusService1.class, serviceOptions);
      return serviceStub.operation(input);
    }
  }

  @WorkflowInterface
  public interface OperationWorkflow {
    @WorkflowMethod
    String execute(String arg);
  }

  public static class TestOperationWorkflow implements OperationWorkflow {
    @Override
    public String execute(String arg) {
      return "Hello from operation workflow " + arg;
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
                          OperationWorkflow.class,
                          WorkflowOptions.newBuilder()
                              .setWorkflowId(details.getRequestId())
                              .build())
                  ::execute);
    }
  }
}
