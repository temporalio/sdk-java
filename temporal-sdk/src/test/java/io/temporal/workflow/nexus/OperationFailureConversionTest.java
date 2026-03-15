package io.temporal.workflow.nexus;

import io.nexusrpc.handler.HandlerException;
import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.NexusOperationFailure;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.*;
import io.temporal.workflow.shared.TestNexusServices;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class OperationFailureConversionTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestNexus.class)
          .setNexusServiceImplementation(new TestNexusServiceImpl())
          .build();

  @Test
  public void nexusOperationApplicationFailureNonRetryableFailureConversion() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    WorkflowFailedException exception =
        Assert.assertThrows(
            WorkflowFailedException.class,
            () -> workflowStub.execute("ApplicationFailureNonRetryable"));
    Assert.assertTrue(exception.getCause() instanceof NexusOperationFailure);
    NexusOperationFailure nexusFailure = (NexusOperationFailure) exception.getCause();
    Assert.assertTrue(nexusFailure.getCause() instanceof HandlerException);
    HandlerException handlerException = (HandlerException) nexusFailure.getCause();
    Assert.assertEquals(HandlerException.ErrorType.INTERNAL, handlerException.getErrorType());
    Assert.assertTrue(
        handlerException.getCause().getMessage().contains("failed to call operation"));
  }

  @Test
  public void nexusOperationApplicationFailureFailureConversion() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    WorkflowFailedException exception =
        Assert.assertThrows(
            WorkflowFailedException.class, () -> workflowStub.execute("ApplicationFailure"));
    Assert.assertTrue(exception.getCause() instanceof NexusOperationFailure);
    NexusOperationFailure nexusFailure = (NexusOperationFailure) exception.getCause();
    Assert.assertTrue(nexusFailure.getCause() instanceof HandlerException);
    HandlerException handlerFailure = (HandlerException) nexusFailure.getCause();
    Assert.assertEquals(HandlerException.ErrorType.INTERNAL, handlerFailure.getErrorType());
    Assert.assertTrue(handlerFailure.getCause().getMessage().contains("exceeded invocation count"));
  }

  @Test
  public void nexusOperationWorkflowNotFoundFailureConversion() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    WorkflowFailedException exception =
        Assert.assertThrows(
            WorkflowFailedException.class, () -> workflowStub.execute("WorkflowNotFound"));
    Assert.assertTrue(exception.getCause() instanceof NexusOperationFailure);
    NexusOperationFailure nexusFailure = (NexusOperationFailure) exception.getCause();
    Assert.assertTrue(nexusFailure.getCause() instanceof HandlerException);
    HandlerException handlerFailure = (HandlerException) nexusFailure.getCause();
    Assert.assertEquals(HandlerException.ErrorType.NOT_FOUND, handlerFailure.getErrorType());
    Assert.assertTrue(handlerFailure.getCause() instanceof ApplicationFailure);
    ApplicationFailure applicationFailure = (ApplicationFailure) handlerFailure.getCause();
    Assert.assertEquals(
        "io.temporal.client.WorkflowNotFoundException", applicationFailure.getType());
  }

  public static class TestNexus implements TestWorkflow1 {
    @Override
    public String execute(String testcase) {
      NexusOperationOptions options =
          NexusOperationOptions.newBuilder()
              .setScheduleToCloseTimeout(Duration.ofSeconds(5))
              .build();

      NexusServiceOptions serviceOptions =
          NexusServiceOptions.newBuilder().setOperationOptions(options).build();
      TestNexusServices.TestNexusService1 testNexusService =
          Workflow.newNexusServiceStub(TestNexusServices.TestNexusService1.class, serviceOptions);
      testNexusService.operation(testcase);
      return "fail";
    }
  }

  @ServiceImpl(service = TestNexusServices.TestNexusService1.class)
  public class TestNexusServiceImpl {
    Map<String, Integer> invocationCount = new ConcurrentHashMap<>();

    @OperationImpl
    public OperationHandler<String, String> operation() {
      return OperationHandler.sync(
          (ctx, details, name) -> {
            invocationCount.put(
                details.getRequestId(),
                invocationCount.getOrDefault(details.getRequestId(), 0) + 1);
            if (name.equals("ApplicationFailure")) {
              // Limit the number of retries to 2 to avoid overwhelming the test server
              if (invocationCount.get(details.getRequestId()) >= 2) {
                throw ApplicationFailure.newNonRetryableFailure(
                    "exceeded invocation count", "ExceededInvocationCount");
              }
              throw ApplicationFailure.newFailure("failed to call operation", "TestFailure");
            } else if (name.equals("ApplicationFailureNonRetryable")) {
              throw ApplicationFailure.newNonRetryableFailure(
                  "failed to call operation", "TestFailure");
            } else if (name.equals("WorkflowNotFound")) {
              throw new WorkflowNotFoundException(
                  WorkflowExecution.getDefaultInstance(), "TestWorkflowType", null);
            }
            Assert.fail();
            return "fail";
          });
    }
  }
}
