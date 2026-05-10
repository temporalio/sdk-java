package io.temporal.worker;

import static org.junit.Assert.*;

import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.api.enums.v1.TaskQueueType;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import io.temporal.workflow.shared.TestNexusServices;
import java.util.List;
import org.junit.Test;

public class ActiveTaskQueueTypesTest {

  @WorkflowInterface
  public interface TestWorkflow {
    @WorkflowMethod
    void run();
  }

  public static class TestWorkflowImpl implements TestWorkflow {
    @Override
    public void run() {}
  }

  @ActivityInterface
  public interface TestActivity {
    @ActivityMethod
    void doThing();
  }

  public static class TestActivityImpl implements TestActivity {
    @Override
    public void doThing() {}
  }

  @ServiceImpl(service = TestNexusServices.TestNexusService1.class)
  public static class TestNexusServiceImpl {
    @OperationImpl
    public OperationHandler<String, String> operation() {
      return OperationHandler.sync((ctx, details, now) -> "Hello " + now);
    }
  }

  @Test
  public void testNoRegistrations() {
    try (TestWorkflowEnvironment env = newEnv()) {
      Worker worker = env.newWorker("test-queue");
      List<TaskQueueType> types = worker.getActiveTaskQueueTypes();
      assertTrue("no types should be active without registrations", types.isEmpty());
    }
  }

  @Test
  public void testWorkflowOnly() {
    try (TestWorkflowEnvironment env = newEnv()) {
      Worker worker = env.newWorker("test-queue");
      worker.registerWorkflowImplementationTypes(TestWorkflowImpl.class);

      List<TaskQueueType> types = worker.getActiveTaskQueueTypes();
      assertEquals(1, types.size());
      assertTrue(types.contains(TaskQueueType.TASK_QUEUE_TYPE_WORKFLOW));
    }
  }

  @Test
  public void testWorkflowAndActivity() {
    try (TestWorkflowEnvironment env = newEnv()) {
      Worker worker = env.newWorker("test-queue");
      worker.registerWorkflowImplementationTypes(TestWorkflowImpl.class);
      worker.registerActivitiesImplementations(new TestActivityImpl());

      List<TaskQueueType> types = worker.getActiveTaskQueueTypes();
      assertEquals(2, types.size());
      assertTrue(types.contains(TaskQueueType.TASK_QUEUE_TYPE_WORKFLOW));
      assertTrue(types.contains(TaskQueueType.TASK_QUEUE_TYPE_ACTIVITY));
    }
  }

  @Test
  public void testAllTypes() {
    try (TestWorkflowEnvironment env = newEnv()) {
      Worker worker = env.newWorker("test-queue");
      worker.registerWorkflowImplementationTypes(TestWorkflowImpl.class);
      worker.registerActivitiesImplementations(new TestActivityImpl());
      worker.registerNexusServiceImplementation(new TestNexusServiceImpl());

      List<TaskQueueType> types = worker.getActiveTaskQueueTypes();
      assertEquals(3, types.size());
      assertTrue(types.contains(TaskQueueType.TASK_QUEUE_TYPE_WORKFLOW));
      assertTrue(types.contains(TaskQueueType.TASK_QUEUE_TYPE_ACTIVITY));
      assertTrue(types.contains(TaskQueueType.TASK_QUEUE_TYPE_NEXUS));
    }
  }

  @Test
  public void testLocalActivityWorkerOnlyExcludesActivity() {
    try (TestWorkflowEnvironment env = newEnv()) {
      Worker worker =
          env.newWorker(
              "test-queue", WorkerOptions.newBuilder().setLocalActivityWorkerOnly(true).build());
      worker.registerWorkflowImplementationTypes(TestWorkflowImpl.class);
      worker.registerActivitiesImplementations(new TestActivityImpl());

      List<TaskQueueType> types = worker.getActiveTaskQueueTypes();
      assertEquals(1, types.size());
      assertTrue(types.contains(TaskQueueType.TASK_QUEUE_TYPE_WORKFLOW));
      assertFalse(types.contains(TaskQueueType.TASK_QUEUE_TYPE_ACTIVITY));
    }
  }

  private static TestWorkflowEnvironment newEnv() {
    return TestWorkflowEnvironment.newInstance(TestEnvironmentOptions.newBuilder().build());
  }
}
