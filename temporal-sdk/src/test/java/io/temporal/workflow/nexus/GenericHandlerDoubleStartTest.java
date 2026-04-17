package io.temporal.workflow.nexus;

import io.nexusrpc.Operation;
import io.nexusrpc.Service;
import io.nexusrpc.handler.HandlerException;
import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.NexusOperationFailure;
import io.temporal.nexus.TemporalOperationHandler;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.*;
import io.temporal.workflow.shared.TestMultiArgWorkflowFunctions;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class GenericHandlerDoubleStartTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              TestNexus.class, TestMultiArgWorkflowFunctions.TestMultiArgWorkflowImpl.class)
          .setNexusServiceImplementation(new TestNexusServiceImpl())
          .build();

  private static final String EXPECTED_MESSAGE =
      "Only one async operation can be started per operation handler "
          + "invocation. Use getWorkflowClient() for additional workflow interactions.";

  @Test
  public void doubleStartThrows() {
    TestWorkflows.TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.TestWorkflow1.class);

    WorkflowFailedException e =
        Assert.assertThrows(
            WorkflowFailedException.class,
            () -> workflowStub.execute(testWorkflowRule.getTaskQueue()));

    Assert.assertTrue(e.getCause() instanceof NexusOperationFailure);
    NexusOperationFailure nexusFailure = (NexusOperationFailure) e.getCause();

    Assert.assertTrue(nexusFailure.getCause() instanceof HandlerException);
    HandlerException handlerException = (HandlerException) nexusFailure.getCause();
    Assert.assertEquals("handler error: " + EXPECTED_MESSAGE, handlerException.getMessage());

    Assert.assertTrue(handlerException.getCause() instanceof ApplicationFailure);
    ApplicationFailure appFailure = (ApplicationFailure) handlerException.getCause();
    Assert.assertEquals("java.lang.IllegalStateException", appFailure.getType());
    Assert.assertEquals(EXPECTED_MESSAGE, appFailure.getOriginalMessage());
  }

  public static class TestNexus implements TestWorkflows.TestWorkflow1 {
    @Override
    public String execute(String input) {
      NexusOperationOptions options =
          NexusOperationOptions.newBuilder()
              .setScheduleToCloseTimeout(Duration.ofSeconds(10))
              .build();
      NexusServiceOptions serviceOptions =
          NexusServiceOptions.newBuilder().setOperationOptions(options).build();

      TestNexusServiceDoubleStart serviceStub =
          Workflow.newNexusServiceStub(TestNexusServiceDoubleStart.class, serviceOptions);
      return serviceStub.operation("input");
    }
  }

  @Service
  public interface TestNexusServiceDoubleStart {
    @Operation
    String operation(String input);
  }

  @ServiceImpl(service = TestNexusServiceDoubleStart.class)
  public class TestNexusServiceImpl {
    @OperationImpl
    public OperationHandler<String, String> operation() {
      return TemporalOperationHandler.create(
          (context, client, input) -> {
            // First start should succeed
            client.startWorkflow(
                TestMultiArgWorkflowFunctions.Test1ArgWorkflowFunc.class,
                TestMultiArgWorkflowFunctions.Test1ArgWorkflowFunc::func1,
                input,
                WorkflowOptions.newBuilder()
                    .setWorkflowId("double-start-first-" + context.getService())
                    .build());
            // Second start should throw
            return client.startWorkflow(
                TestMultiArgWorkflowFunctions.Test1ArgWorkflowFunc.class,
                TestMultiArgWorkflowFunctions.Test1ArgWorkflowFunc::func1,
                input,
                WorkflowOptions.newBuilder()
                    .setWorkflowId("double-start-second-" + context.getService())
                    .build());
          });
    }
  }
}
