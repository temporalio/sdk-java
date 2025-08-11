package io.temporal.workflow.nexus;

import io.nexusrpc.OperationException;
import io.nexusrpc.OperationInfo;
import io.nexusrpc.OperationStillRunningException;
import io.nexusrpc.client.CompleteOperationOptions;
import io.nexusrpc.client.CompletionClient;
import io.nexusrpc.handler.*;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowException;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestNexusServices;
import io.temporal.workflow.shared.TestWorkflows;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class NexusOperationClientTest {
  static String url;
  static Map<String, String> headers;
  static CountDownLatch latch = new CountDownLatch(1);

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestNexus.class)
          .setNexusServiceImplementation(new TestNexusServiceImpl())
          .build();

  @Test
  public void testCompletionClientSucceed() throws InterruptedException {
    TestWorkflows.TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.TestWorkflow1.class);
    // Start the workflow
    WorkflowClient.start(workflowStub::execute, testWorkflowRule.getTaskQueue());
    // Wait for the operation to start
    latch.await();
    // Complete the operation
    CompletionClient nexusCompletionClient =
        testWorkflowRule.getWorkflowClient().newNexusCompletionClient();
    Thread.sleep(100);
    nexusCompletionClient.succeedOperation(
        url, "result", CompleteOperationOptions.newBuilder().setHeaders(headers).build());
    // Wait for the workflow to complete
    String result = workflowStub.execute(testWorkflowRule.getTaskQueue());
    Assert.assertEquals("result", result);
  }

  @Test
  public void testCompletionClientFail() throws InterruptedException {
    TestWorkflows.TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.TestWorkflow1.class);
    // Start the workflow
    WorkflowClient.start(workflowStub::execute, testWorkflowRule.getTaskQueue());
    // Wait for the operation to start
    latch.await();
    // Complete the operation
    CompletionClient nexusCompletionClient =
        testWorkflowRule.getWorkflowClient().newNexusCompletionClient();
    Thread.sleep(100);
    nexusCompletionClient.failOperation(
        url,
        OperationException.failure(new Exception("test failure")),
        CompleteOperationOptions.newBuilder().setHeaders(headers).build());
    // Wait for the workflow to complete with a failure
    WorkflowException we =
        Assert.assertThrows(
            WorkflowException.class, () -> workflowStub.execute(testWorkflowRule.getTaskQueue()));
    System.out.println(we);
  }

  public static class TestNexus implements TestWorkflows.TestWorkflow1 {
    @Override
    public String execute(String input) {
      // Try to call with the typed stub
      TestNexusServices.TestNexusService1 serviceStub =
          Workflow.newNexusServiceStub(TestNexusServices.TestNexusService1.class);
      return serviceStub.operation(input);
    }
  }

  @ServiceImpl(service = TestNexusServices.TestNexusService1.class)
  public static class TestNexusServiceImpl {
    @OperationImpl
    public OperationHandler<String, String> operation() {
      return new TestAsyncHandler();
    }
  }

  public static class TestAsyncHandler implements OperationHandler<String, String> {

    @Override
    public OperationStartResult<String> start(
        OperationContext context, OperationStartDetails details, String param)
        throws OperationException, HandlerException {
      url = details.getCallbackUrl();
      headers = details.getCallbackHeaders();
      latch.countDown();
      return OperationStartResult.async("token-" + param);
    }

    @Override
    public String fetchResult(OperationContext context, OperationFetchResultDetails details)
        throws OperationStillRunningException, OperationException, HandlerException {
      return "";
    }

    @Override
    public OperationInfo fetchInfo(OperationContext context, OperationFetchInfoDetails details)
        throws HandlerException {
      return null;
    }

    @Override
    public void cancel(OperationContext context, OperationCancelDetails details)
        throws HandlerException {}
  }
}
