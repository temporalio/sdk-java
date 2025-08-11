package io.temporal.client;

import io.nexusrpc.OperationException;
import io.nexusrpc.OperationState;
import io.nexusrpc.OperationStillRunningException;
import io.nexusrpc.client.ServiceClient;
import io.nexusrpc.client.StartOperationResponse;
import io.nexusrpc.handler.*;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestNexusServices;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class NexusServiceClientSyncOperationTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setNexusServiceImplementation(new TestNexusServiceImpl())
          .build();

  @Test
  public void executeSyncOperation()
      throws OperationException,
          OperationStillRunningException,
          ExecutionException,
          InterruptedException {
    ServiceClient<TestNexusServices.TestNexusService1> serviceClient =
        testWorkflowRule
            .getWorkflowClient()
            .newNexusServiceClient(
                TestNexusServices.TestNexusService1.class,
                testWorkflowRule.getNexusEndpoint().getSpec().getName());

    String result =
        serviceClient.executeOperation(TestNexusServices.TestNexusService1::operation, "World");
    Assert.assertEquals("Hello World", result);

    String asyncResult =
        serviceClient
            .executeOperationAsync(TestNexusServices.TestNexusService1::operation, "World Async")
            .get();
    Assert.assertEquals("Hello World Async", asyncResult);
  }

  @Test
  public void executeSyncOperationFail() {
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
                    TestNexusServices.TestNexusService1::operation, "fail"));
    Assert.assertEquals(OperationState.FAILED, oe.getState());
    Assert.assertTrue(oe.getCause() instanceof ApplicationFailure);

    CompletableFuture<String> result =
        serviceClient.executeOperationAsync(TestNexusServices.TestNexusService1::operation, "fail");
    ExecutionException ee = Assert.assertThrows(ExecutionException.class, result::get);
    Assert.assertTrue(ee.getCause() instanceof OperationException);
    oe = (OperationException) ee.getCause();
    Assert.assertEquals(OperationState.FAILED, oe.getState());
    Assert.assertTrue(oe.getCause() instanceof ApplicationFailure);
  }

  @Test
  public void executeSyncOperationHandlerError() {
    ServiceClient<TestNexusServices.TestNexusService1> serviceClient =
        testWorkflowRule
            .getWorkflowClient()
            .newNexusServiceClient(
                TestNexusServices.TestNexusService1.class,
                testWorkflowRule.getNexusEndpoint().getSpec().getName());

    HandlerException he =
        Assert.assertThrows(
            HandlerException.class,
            () ->
                serviceClient.executeOperation(
                    TestNexusServices.TestNexusService1::operation, "handlerError"));
    System.out.println(he.getMessage());
  }

  @Test
  public void executeSyncOperationCancel() {
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
                    TestNexusServices.TestNexusService1::operation, "cancel"));
    Assert.assertEquals(OperationState.CANCELED, oe.getState());
    Assert.assertTrue(oe.getCause() instanceof ApplicationFailure);

    CompletableFuture<String> result =
        serviceClient.executeOperationAsync(
            TestNexusServices.TestNexusService1::operation, "cancel");
    ExecutionException ee = Assert.assertThrows(ExecutionException.class, result::get);
    Assert.assertTrue(ee.getCause() instanceof OperationException);
    oe = (OperationException) ee.getCause();
    Assert.assertEquals(OperationState.CANCELED, oe.getState());
    Assert.assertTrue(oe.getCause() instanceof ApplicationFailure);
  }

  @Test
  public void startSyncOperation() throws OperationException {
    ServiceClient<TestNexusServices.TestNexusService1> serviceClient =
        testWorkflowRule
            .getWorkflowClient()
            .newNexusServiceClient(
                TestNexusServices.TestNexusService1.class,
                testWorkflowRule.getNexusEndpoint().getSpec().getName());

    StartOperationResponse<String> result =
        serviceClient.startOperation(TestNexusServices.TestNexusService1::operation, "World");
    Assert.assertTrue(result instanceof StartOperationResponse.Sync);
    StartOperationResponse.Sync<String> syncResult = (StartOperationResponse.Sync<String>) result;
    Assert.assertEquals("Hello World", syncResult.getResult());

    CompletableFuture<StartOperationResponse<String>> asyncResult =
        serviceClient.startOperationAsync(
            TestNexusServices.TestNexusService1::operation, "World Async");
    Assert.assertTrue(asyncResult.join() instanceof StartOperationResponse.Sync);
    syncResult = (StartOperationResponse.Sync<String>) asyncResult.join();
    Assert.assertEquals("Hello World Async", syncResult.getResult());
  }

  @Test
  public void startSyncOperationFail() {
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
                serviceClient.startOperation(
                    TestNexusServices.TestNexusService1::operation, "fail"));
    Assert.assertEquals(OperationState.FAILED, oe.getState());
    Assert.assertTrue(oe.getCause() instanceof ApplicationFailure);

    CompletableFuture<StartOperationResponse<String>> asyncResult =
        serviceClient.startOperationAsync(TestNexusServices.TestNexusService1::operation, "fail");
    ExecutionException ee = Assert.assertThrows(ExecutionException.class, asyncResult::get);
    Assert.assertTrue(ee.getCause() instanceof OperationException);
    oe = (OperationException) ee.getCause();
    Assert.assertEquals(OperationState.FAILED, oe.getState());
    Assert.assertTrue(oe.getCause() instanceof ApplicationFailure);
  }

  @Test
  public void startSyncOperationCancel() {
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
                serviceClient.startOperation(
                    TestNexusServices.TestNexusService1::operation, "cancel"));
    Assert.assertEquals(OperationState.CANCELED, oe.getState());
    Assert.assertTrue(oe.getCause() instanceof ApplicationFailure);

    CompletableFuture<StartOperationResponse<String>> asyncResult =
        serviceClient.startOperationAsync(TestNexusServices.TestNexusService1::operation, "cancel");
    ExecutionException ee = Assert.assertThrows(ExecutionException.class, asyncResult::get);
    Assert.assertTrue(ee.getCause() instanceof OperationException);
    oe = (OperationException) ee.getCause();
    Assert.assertEquals(OperationState.CANCELED, oe.getState());
    Assert.assertTrue(oe.getCause() instanceof ApplicationFailure);
  }

  @ServiceImpl(service = TestNexusServices.TestNexusService1.class)
  public static class TestNexusServiceImpl {
    @OperationImpl
    public OperationHandler<String, String> operation() {
      return OperationHandler.sync(
          (OperationContext context, OperationStartDetails details, String param) -> {
            if (Objects.equals(param, "fail")) {
              throw OperationException.failure(new IllegalArgumentException("fail"));
            } else if (Objects.equals(param, "cancel")) {
              throw OperationException.canceled(new IllegalArgumentException("cancel"));
            } else if (Objects.equals(param, "handlerError")) {
              throw new HandlerException(
                  HandlerException.ErrorType.RESOURCE_EXHAUSTED,
                  new IllegalArgumentException("handlerError"),
                  HandlerException.RetryBehavior.RETRYABLE);
            }
            return "Hello " + param;
          });
    }
  }
}
