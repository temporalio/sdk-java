package io.temporal.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.nexusrpc.OperationException;
import io.nexusrpc.OperationInfo;
import io.nexusrpc.OperationState;
import io.nexusrpc.OperationStillRunningException;
import io.nexusrpc.client.*;
import io.nexusrpc.handler.*;
import io.temporal.failure.ApplicationFailure;
import io.temporal.internal.nexus.OperationTokenUtil;
import io.temporal.nexus.Nexus;
import io.temporal.nexus.WorkflowRunOperation;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestNexusServices;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class NexusServiceClientWorkflowOperationTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestNexus.class)
          .setNexusServiceImplementation(new TestNexusServiceImpl())
          .setUseExternalService(true)
          .build();

  @Test(timeout = 5000000)
  public void executeWorkflowOperationSuccess()
      throws OperationStillRunningException, OperationException {
    ServiceClient<TestNexusServices.TestNexusService1> serviceClient =
        testWorkflowRule
            .getWorkflowClient()
            .newNexusServiceClient(
                TestNexusServices.TestNexusService1.class,
                testWorkflowRule.getNexusEndpoint().getSpec().getName());

    Assert.assertEquals(
        "Hello World",
        serviceClient.executeOperation(
            TestNexusServices.TestNexusService1::operation,
            "World",
            ExecuteOperationOptions.newBuilder().setTimeout(Duration.ofMinutes(2)).build()));

    CompletableFuture<String> resultAsync =
        serviceClient.executeOperationAsync(
            TestNexusServices.TestNexusService1::operation,
            "World Async",
            ExecuteOperationOptions.newBuilder().setTimeout(Duration.ofMinutes(2)).build());
    Assert.assertEquals("Hello World Async", resultAsync.join());
  }

  @Test
  public void executeWorkflowOperationFail() {
    ServiceClient<TestNexusServices.TestNexusService1> serviceClient =
        testWorkflowRule
            .getWorkflowClient()
            .newNexusServiceClient(
                TestNexusServices.TestNexusService1.class,
                testWorkflowRule.getNexusEndpoint().getSpec().getName());

    OperationException oe =
        Assert.assertThrows(
            OperationException.class,
            () ->
                serviceClient.executeOperation(
                    TestNexusServices.TestNexusService1::operation,
                    "fail",
                    ExecuteOperationOptions.newBuilder()
                        .setTimeout(Duration.ofSeconds(10))
                        .build()));
    Assert.assertTrue(oe.getCause() instanceof ApplicationFailure);

    CompletableFuture<String> resultAsync =
        serviceClient.executeOperationAsync(
            TestNexusServices.TestNexusService1::operation,
            "fail",
            ExecuteOperationOptions.newBuilder().setTimeout(Duration.ofSeconds(10)).build());
    CompletionException ce =
        Assert.assertThrows(CompletionException.class, () -> resultAsync.join());
    Assert.assertTrue(ce.getCause() instanceof OperationException);
    OperationException asyncOperationException = (OperationException) ce.getCause();
    Assert.assertTrue(asyncOperationException.getCause() instanceof ApplicationFailure);
  }

  @Test
  public void executeWorkflowOperationStillRunning() {
    ServiceClient<TestNexusServices.TestNexusService1> serviceClient =
        testWorkflowRule
            .getWorkflowClient()
            .newNexusServiceClient(
                TestNexusServices.TestNexusService1.class,
                testWorkflowRule.getNexusEndpoint().getSpec().getName());

    Assert.assertThrows(
        OperationStillRunningException.class,
        () ->
            serviceClient.executeOperation(
                TestNexusServices.TestNexusService1::operation,
                "World",
                ExecuteOperationOptions.newBuilder().setTimeout(Duration.ofSeconds(1)).build()));

    CompletableFuture<String> resultAsync =
        serviceClient.executeOperationAsync(
            TestNexusServices.TestNexusService1::operation,
            "World Async",
            ExecuteOperationOptions.newBuilder().setTimeout(Duration.ofSeconds(1)).build());
    CompletionException ce =
        Assert.assertThrows(CompletionException.class, () -> resultAsync.join());
    Assert.assertTrue(ce.getCause() instanceof OperationStillRunningException);
  }

  @Test
  public void createHandle()
      throws OperationException, OperationStillRunningException, InterruptedException {
    ServiceClient<TestNexusServices.TestNexusService1> serviceClient =
        testWorkflowRule
            .getWorkflowClient()
            .newNexusServiceClient(
                TestNexusServices.TestNexusService1.class,
                testWorkflowRule.getNexusEndpoint().getSpec().getName());

    StartOperationResult<String> startResult =
        serviceClient.startOperation(TestNexusServices.TestNexusService1::operation, "World");
    Assert.assertTrue(startResult instanceof StartOperationResult.Async);
    OperationHandle<String> handle = ((StartOperationResult.Async<String>) startResult).getHandle();
    OperationHandle<String> newHandler =
        serviceClient.newHandle(
            TestNexusServices.TestNexusService1::operation, handle.getOperationToken());
    Thread.sleep(6000); // Wait for the operation to complete
    String operationResult =
        newHandler.fetchResult(
            FetchOperationResultOptions.newBuilder().setTimeout(Duration.ofSeconds(1)).build());
    Assert.assertEquals("Hello World", operationResult);
  }

  @Test
  public void createHandleUntyped()
      throws OperationException, OperationStillRunningException, InterruptedException {
    ServiceClient<TestNexusServices.TestNexusService1> serviceClient =
        testWorkflowRule
            .getWorkflowClient()
            .newNexusServiceClient(
                TestNexusServices.TestNexusService1.class,
                testWorkflowRule.getNexusEndpoint().getSpec().getName());

    StartOperationResult<String> startResult =
        serviceClient.startOperation(TestNexusServices.TestNexusService1::operation, "World");
    Assert.assertTrue(startResult instanceof StartOperationResult.Async);
    OperationHandle<String> handle = ((StartOperationResult.Async<String>) startResult).getHandle();

    OperationHandle<String> newHandler =
        serviceClient.newHandle(
            TestNexusServices.TestNexusService1::operation, handle.getOperationToken());
    Thread.sleep(6000); // Wait for the operation to complete
    String operationResult =
        newHandler.fetchResult(
            FetchOperationResultOptions.newBuilder().setTimeout(Duration.ofSeconds(1)).build());
    Assert.assertEquals("Hello World", operationResult);
  }

  @Test
  public void createInvalidHandle() throws JsonProcessingException {
    ServiceClient<TestNexusServices.TestNexusService1> serviceClient =
        testWorkflowRule
            .getWorkflowClient()
            .newNexusServiceClient(
                TestNexusServices.TestNexusService1.class,
                testWorkflowRule.getNexusEndpoint().getSpec().getName());

    OperationHandle<String> badHandle =
        serviceClient.newHandle(TestNexusServices.TestNexusService1::operation, "BAD_TOKEN");
    HandlerException he =
        Assert.assertThrows(
            HandlerException.class,
            () ->
                badHandle.fetchResult(
                    FetchOperationResultOptions.newBuilder()
                        .setTimeout(Duration.ofSeconds(1))
                        .build()));
    System.out.println("Expected exception: " + he.getMessage());

    String token = OperationTokenUtil.generateWorkflowRunOperationToken("workflowId", "namespace");
    OperationHandle<String> missingHandle =
        serviceClient.newHandle(TestNexusServices.TestNexusService1::operation, token);
    he =
        Assert.assertThrows(
            HandlerException.class,
            () ->
                missingHandle.fetchResult(
                    FetchOperationResultOptions.newBuilder()
                        .setTimeout(Duration.ofSeconds(5))
                        .build()));
    Assert.assertEquals(HandlerException.ErrorType.NOT_FOUND, he.getErrorType());

    he = Assert.assertThrows(HandlerException.class, missingHandle::cancel);
    Assert.assertEquals(HandlerException.ErrorType.NOT_FOUND, he.getErrorType());

    he = Assert.assertThrows(HandlerException.class, missingHandle::getInfo);
    Assert.assertEquals(HandlerException.ErrorType.NOT_FOUND, he.getErrorType());
  }

  @Test
  public void startAsyncOperation() throws OperationException, OperationStillRunningException {
    ServiceClient<TestNexusServices.TestNexusService1> serviceClient =
        testWorkflowRule
            .getWorkflowClient()
            .newNexusServiceClient(
                TestNexusServices.TestNexusService1.class,
                testWorkflowRule.getNexusEndpoint().getSpec().getName());

    StartOperationResult<String> startResult =
        serviceClient.startOperation(TestNexusServices.TestNexusService1::operation, "World");
    Assert.assertTrue(startResult instanceof StartOperationResult.Async);
    OperationHandle<String> handle = ((StartOperationResult.Async<String>) startResult).getHandle();
    Assert.assertThrows(
        OperationStillRunningException.class,
        () ->
            handle.fetchResult(
                FetchOperationResultOptions.newBuilder()
                    .setTimeout(Duration.ofSeconds(1))
                    .build()));
    Assert.assertEquals(OperationState.RUNNING, handle.getInfo().getState());
    // Thread.sleep(6000); // Wait for the operation to complete
    String operationResult =
        handle.fetchResult(
            FetchOperationResultOptions.newBuilder().setTimeout(Duration.ofSeconds(10)).build());
    Assert.assertEquals("Hello World", operationResult);
    Assert.assertEquals(OperationState.SUCCEEDED, handle.getInfo().getState());
  }

  @Test
  public void cancelAsyncOperation() throws OperationException {
    ServiceClient<TestNexusServices.TestNexusService1> serviceClient =
        testWorkflowRule
            .getWorkflowClient()
            .newNexusServiceClient(
                TestNexusServices.TestNexusService1.class,
                testWorkflowRule.getNexusEndpoint().getSpec().getName());

    StartOperationResult<String> startResult =
        serviceClient.startOperation(TestNexusServices.TestNexusService1::operation, "World");
    Assert.assertTrue(startResult instanceof StartOperationResult.Async);
    OperationHandle<String> handle = ((StartOperationResult.Async<String>) startResult).getHandle();
    handle.cancel();
    // Verify that we can call cancel again without issues
    handle.cancel();
    OperationException oe =
        Assert.assertThrows(
            OperationException.class,
            () ->
                handle.fetchResult(
                    FetchOperationResultOptions.newBuilder()
                        .setTimeout(Duration.ofSeconds(5))
                        .build()));
    Assert.assertEquals(OperationState.CANCELED, oe.getState());
    // Verify that we can call cancel after the operation is already completed
    handle.cancel();
  }

  @Test
  public void cancelAsyncOperationAsync() {
    ServiceClient<TestNexusServices.TestNexusService1> serviceClient =
        testWorkflowRule
            .getWorkflowClient()
            .newNexusServiceClient(
                TestNexusServices.TestNexusService1.class,
                testWorkflowRule.getNexusEndpoint().getSpec().getName());

    StartOperationResult<String> startResult =
        serviceClient
            .startOperationAsync(TestNexusServices.TestNexusService1::operation, "World")
            .join();
    Assert.assertTrue(startResult instanceof StartOperationResult.Async);
    OperationHandle<String> handle = ((StartOperationResult.Async<String>) startResult).getHandle();
    Assert.assertNotNull(handle);
    handle.cancelAsync().join();
    CompletionException ce =
        Assert.assertThrows(
            CompletionException.class,
            () ->
                handle
                    .fetchResultAsync(
                        FetchOperationResultOptions.newBuilder()
                            .setTimeout(Duration.ofSeconds(5))
                            .build())
                    .join());
    Assert.assertTrue(ce.getCause() instanceof OperationException);
    OperationException oe = (OperationException) ce.getCause();
    Assert.assertEquals(OperationState.CANCELED, oe.getState());
  }

  @Test
  public void startAsyncOperationAsync() {
    ServiceClient<TestNexusServices.TestNexusService1> serviceClient =
        testWorkflowRule
            .getWorkflowClient()
            .newNexusServiceClient(
                TestNexusServices.TestNexusService1.class,
                testWorkflowRule.getNexusEndpoint().getSpec().getName());

    StartOperationResult<String> startResult =
        serviceClient
            .startOperationAsync(TestNexusServices.TestNexusService1::operation, "World")
            .join();
    Assert.assertTrue(startResult instanceof StartOperationResult.Async);
    OperationHandle<String> handle = ((StartOperationResult.Async<String>) startResult).getHandle();
    Assert.assertEquals(OperationState.RUNNING, handle.fetchInfoAsync().join().getState());
    CompletionException ce =
        Assert.assertThrows(
            CompletionException.class,
            () ->
                handle
                    .fetchResultAsync(
                        FetchOperationResultOptions.newBuilder()
                            .setTimeout(Duration.ofSeconds(1))
                            .build())
                    .join());
    Assert.assertTrue(ce.getCause() instanceof OperationStillRunningException);
    // Thread.sleep(6000); // Wait for the operation to complete
    String operationResult =
        handle
            .fetchResultAsync(
                FetchOperationResultOptions.newBuilder().setTimeout(Duration.ofSeconds(10)).build())
            .join();
    Assert.assertEquals("Hello World", operationResult);
    Assert.assertEquals(OperationState.SUCCEEDED, handle.fetchInfoAsync().join().getState());
  }

  @Test
  public void startWorkflowOperationFail() throws OperationException {
    ServiceClient<TestNexusServices.TestNexusService1> serviceClient =
        testWorkflowRule
            .getWorkflowClient()
            .newNexusServiceClient(
                TestNexusServices.TestNexusService1.class,
                testWorkflowRule.getNexusEndpoint().getSpec().getName());

    StartOperationResult<String> startResult =
        serviceClient.startOperation(TestNexusServices.TestNexusService1::operation, "fail");
    Assert.assertTrue(startResult instanceof StartOperationResult.Async);
    OperationHandle<String> handle = ((StartOperationResult.Async<String>) startResult).getHandle();
    OperationException oe =
        Assert.assertThrows(
            OperationException.class,
            () ->
                handle.fetchResult(
                    FetchOperationResultOptions.newBuilder()
                        .setTimeout(Duration.ofSeconds(5))
                        .build()));
    Assert.assertTrue(oe.getCause() instanceof ApplicationFailure);

    OperationInfo info = handle.getInfo();
    Assert.assertEquals(OperationState.FAILED, info.getState());
  }

  public static class TestNexus implements TestWorkflows.TestWorkflow1 {

    @Override
    public String execute(String arg) {
      if (Objects.equals(arg, "fail")) {
        throw ApplicationFailure.newNonRetryableFailure("fail workflow", "TestError");
      }
      Workflow.sleep(Duration.ofSeconds(5));
      return "Hello " + arg;
    }
  }

  @ServiceImpl(service = TestNexusServices.TestNexusService1.class)
  public static class TestNexusServiceImpl {
    @OperationImpl
    public OperationHandler<String, String> operation() {
      return WorkflowRunOperation.fromWorkflowMethod(
          (context, details, input) ->
              Nexus.getOperationContext()
                      .getWorkflowClient()
                      .newWorkflowStub(
                          TestWorkflows.TestWorkflow1.class,
                          WorkflowOptions.newBuilder()
                              .setWorkflowId(details.getRequestId())
                              .build())
                  ::execute);
    }
  }
}
