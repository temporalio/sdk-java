package io.temporal.worker;

import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestNexusServices;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class WorkerRegistrationTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setDoNotStart(true).build();

  @Test
  public void testDuplicateRegistration() {
    Worker worker = testWorkflowRule.getWorker();
    worker.registerNexusServiceImplementation(new TestNexusServiceImpl1());
    Assert.assertThrows(
        TypeAlreadyRegisteredException.class,
        () -> worker.registerNexusServiceImplementation(new TestNexusServiceImpl2()));
  }

  @Test
  public void testDuplicateRegistrationInSameCall() {
    Worker worker = testWorkflowRule.getWorker();
    Assert.assertThrows(
        TypeAlreadyRegisteredException.class,
        () ->
            worker.registerNexusServiceImplementation(
                new TestNexusServiceImpl1(), new TestNexusServiceImpl2()));
  }

  @Test
  public void testRegistrationAfterStart() {
    Worker worker = testWorkflowRule.getWorker();
    worker.start();
    Assert.assertThrows(
        IllegalStateException.class,
        () -> worker.registerNexusServiceImplementation(new TestNexusServiceImpl1()));
  }

  @ServiceImpl(service = TestNexusServices.TestNexusService1.class)
  public static class TestNexusServiceImpl1 {
    @OperationImpl
    public OperationHandler<String, String> operation() {
      return OperationHandler.sync((ctx, details, now) -> "Hello " + now);
    }
  }

  @ServiceImpl(service = TestNexusServices.TestNexusService1.class)
  public static class TestNexusServiceImpl2 {
    @OperationImpl
    public OperationHandler<String, String> operation() {
      return OperationHandler.sync((ctx, details, now) -> "Hello " + now);
    }
  }
}
