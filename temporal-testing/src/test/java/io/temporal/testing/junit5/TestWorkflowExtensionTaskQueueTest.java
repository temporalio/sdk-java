package io.temporal.testing.junit5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowExtension;
import io.temporal.testing.junit5.TestWorkflowExtensionTest.HelloActivityImpl;
import io.temporal.testing.junit5.TestWorkflowExtensionTest.HelloWorkflow;
import io.temporal.testing.junit5.TestWorkflowExtensionTest.HelloWorkflowImpl;
import io.temporal.testing.junit5.TestWorkflowExtensionTest.TestNexusServiceImpl;
import io.temporal.worker.Worker;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class TestWorkflowExtensionTaskQueueTest {

  @Test
  public void rejectsEmptyTaskQueue() {
    assertThrows(
        IllegalArgumentException.class,
        () -> TestWorkflowExtension.newBuilder().setWorkerTaskQueue(""));
  }

  @Nested
  class CustomTaskQueue {
    @RegisterExtension
    final TestWorkflowExtension extension =
        TestWorkflowExtension.newBuilder()
            .setWorkerTaskQueue("expense-category")
            .registerWorkflowImplementationTypes(HelloWorkflowImpl.class)
            .setActivityImplementations(new HelloActivityImpl())
            .setNexusServiceImplementation(new TestNexusServiceImpl())
            .build();

    @Test
    void usesCustomTaskQueue(
        Worker worker,
        WorkflowOptions options,
        WorkflowClient client,
        HelloWorkflow injectedWorkflow) {
      assertEquals("expense-category", worker.getTaskQueue());
      assertEquals("expense-category", options.getTaskQueue());
      assertEquals(
          "Hello Injected from activity BuildGreeting and workflow HelloWorkflow",
          injectedWorkflow.sayHello("Injected"));

      HelloWorkflow workflow =
          client.newWorkflowStub(
              HelloWorkflow.class,
              WorkflowOptions.newBuilder().setTaskQueue("expense-category").build());
      assertEquals(
          "Hello Client from activity BuildGreeting and workflow HelloWorkflow",
          workflow.sayHello("Client"));
    }
  }

  @Nested
  class NullTaskQueue {
    @RegisterExtension
    final TestWorkflowExtension extension =
        TestWorkflowExtension.newBuilder()
            .setWorkerTaskQueue("expense-category")
            .setWorkerTaskQueue(null)
            .build();

    @Test
    void restoresGeneratedTaskQueue(Worker worker, WorkflowOptions options) {
      assertTrue(worker.getTaskQueue().startsWith("WorkflowTest-"));
      assertEquals(worker.getTaskQueue(), options.getTaskQueue());
    }
  }
}
