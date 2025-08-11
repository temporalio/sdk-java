package io.temporal.workflow;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.activity.ActivityOptions;
import io.temporal.activity.LocalActivityOptions;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestNexusServices;
import java.time.Duration;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;

public class StubReuseTest {
  static TestActivities.TestActivity1 storedActivity;
  static TestActivities.TestActivity1 storedLocalActivity;
  static SimpleChildWorkflow storedChild;
  static TargetWorkflow storedExternal;
  static TestNexusServices.TestNexusService1 storedNexus;

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              StoreActivityWorkflowImpl.class,
              UseActivityWorkflowImpl.class,
              StoreLocalActivityWorkflowImpl.class,
              UseLocalActivityWorkflowImpl.class,
              StoreChildWorkflowWorkflowImpl.class,
              UseChildWorkflowWorkflowImpl.class,
              TargetWorkflowImpl.class,
              StoreExternalWorkflowImpl.class,
              UseExternalWorkflowImpl.class,
              StoreNexusServiceWorkflowImpl.class,
              UseNexusServiceWorkflowImpl.class,
              SimpleChildWorkflowImpl.class)
          .setActivityImplementations(new SimpleActivityImpl())
          .setNexusServiceImplementation(new TestNexusServiceImpl())
          .build();

  @Test
  public void activityStubReuseFails() {
    StoreActivityWorkflow store =
        testWorkflowRule.newWorkflowStubTimeoutOptions(StoreActivityWorkflow.class);
    store.execute(testWorkflowRule.getTaskQueue());
    UseActivityWorkflow use =
        testWorkflowRule.newWorkflowStubTimeoutOptions(UseActivityWorkflow.class);
    WorkflowFailedException e =
        assertThrows(WorkflowFailedException.class, () -> use.execute("unused"));
    assertTrue(e.getCause().getMessage().contains("belongs to a different workflow"));
  }

  @Test
  public void localActivityStubReuseFails() {
    StoreLocalActivityWorkflow store =
        testWorkflowRule.newWorkflowStubTimeoutOptions(StoreLocalActivityWorkflow.class);
    store.execute(testWorkflowRule.getTaskQueue());
    UseLocalActivityWorkflow use =
        testWorkflowRule.newWorkflowStubTimeoutOptions(UseLocalActivityWorkflow.class);
    WorkflowFailedException e =
        assertThrows(WorkflowFailedException.class, () -> use.execute("unused"));
    assertTrue(e.getCause().getMessage().contains("belongs to a different workflow"));
  }

  @Test
  public void childWorkflowStubReuseFails() {
    StoreChildWorkflowWorkflow store =
        testWorkflowRule.newWorkflowStubTimeoutOptions(StoreChildWorkflowWorkflow.class);
    store.execute(testWorkflowRule.getTaskQueue());
    UseChildWorkflowWorkflow use =
        testWorkflowRule.newWorkflowStubTimeoutOptions(UseChildWorkflowWorkflow.class);
    WorkflowFailedException e =
        assertThrows(WorkflowFailedException.class, () -> use.execute("input"));
    assertTrue(e.getCause().getMessage().contains("belongs to a different workflow"));
  }

  @Test
  public void externalWorkflowStubReuseFails() {
    WorkflowOptions opts =
        WorkflowOptions.newBuilder()
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .setWorkflowId("target-" + UUID.randomUUID())
            .build();
    TargetWorkflow target =
        testWorkflowRule.getWorkflowClient().newWorkflowStub(TargetWorkflow.class, opts);
    WorkflowClient.start(target::execute);

    StoreExternalWorkflow store =
        testWorkflowRule.newWorkflowStubTimeoutOptions(StoreExternalWorkflow.class);
    store.execute(opts.getWorkflowId());
    UseExternalWorkflow use =
        testWorkflowRule.newWorkflowStubTimeoutOptions(UseExternalWorkflow.class);
    WorkflowFailedException e =
        assertThrows(WorkflowFailedException.class, () -> use.execute("unused"));
    assertTrue(e.getCause().getMessage().contains("belongs to a different workflow"));
    target.unblock();
  }

  @Test
  public void nexusServiceStubReuseFails() {
    StoreNexusServiceWorkflow store =
        testWorkflowRule.newWorkflowStubTimeoutOptions(StoreNexusServiceWorkflow.class);
    store.execute(testWorkflowRule.getTaskQueue());
    UseNexusServiceWorkflow use =
        testWorkflowRule.newWorkflowStubTimeoutOptions(UseNexusServiceWorkflow.class);
    WorkflowFailedException e =
        assertThrows(WorkflowFailedException.class, () -> use.execute("input"));
    assertTrue(e.getCause().getMessage().contains("belongs to a different workflow"));
  }

  @WorkflowInterface
  public interface StoreActivityWorkflow {
    @WorkflowMethod(name = "StoreActivity")
    String execute(String input);
  }

  public static class StoreActivityWorkflowImpl implements StoreActivityWorkflow {
    @Override
    public String execute(String input) {
      storedActivity =
          Workflow.newActivityStub(
              TestActivities.TestActivity1.class,
              ActivityOptions.newBuilder()
                  .setScheduleToCloseTimeout(Duration.ofSeconds(5))
                  .build());
      return "";
    }
  }

  @WorkflowInterface
  public interface UseActivityWorkflow {
    @WorkflowMethod(name = "UseActivity")
    String execute(String input);
  }

  public static class UseActivityWorkflowImpl implements UseActivityWorkflow {
    @Override
    public String execute(String input) {
      return storedActivity.execute(input);
    }
  }

  @WorkflowInterface
  public interface StoreLocalActivityWorkflow {
    @WorkflowMethod(name = "StoreLocalActivity")
    String execute(String input);
  }

  public static class StoreLocalActivityWorkflowImpl implements StoreLocalActivityWorkflow {
    @Override
    public String execute(String input) {
      storedLocalActivity =
          Workflow.newLocalActivityStub(
              TestActivities.TestActivity1.class,
              LocalActivityOptions.newBuilder()
                  .setScheduleToCloseTimeout(Duration.ofSeconds(5))
                  .build());
      return "";
    }
  }

  @WorkflowInterface
  public interface UseLocalActivityWorkflow {
    @WorkflowMethod(name = "UseLocalActivity")
    String execute(String input);
  }

  public static class UseLocalActivityWorkflowImpl implements UseLocalActivityWorkflow {
    @Override
    public String execute(String input) {
      return storedLocalActivity.execute(input);
    }
  }

  @WorkflowInterface
  public interface StoreChildWorkflowWorkflow {
    @WorkflowMethod(name = "StoreChild")
    String execute(String input);
  }

  public static class StoreChildWorkflowWorkflowImpl implements StoreChildWorkflowWorkflow {
    @Override
    public String execute(String input) {
      storedChild = Workflow.newChildWorkflowStub(SimpleChildWorkflow.class);
      return "";
    }
  }

  @WorkflowInterface
  public interface UseChildWorkflowWorkflow {
    @WorkflowMethod(name = "UseChild")
    String execute(String input);
  }

  public static class UseChildWorkflowWorkflowImpl implements UseChildWorkflowWorkflow {
    @Override
    public String execute(String input) {
      return storedChild.run(input);
    }
  }

  @WorkflowInterface
  public interface StoreExternalWorkflow {
    @WorkflowMethod(name = "StoreExternal")
    String execute(String workflowId);
  }

  public static class StoreExternalWorkflowImpl implements StoreExternalWorkflow {
    @Override
    public String execute(String workflowId) {
      WorkflowExecution exec = WorkflowExecution.newBuilder().setWorkflowId(workflowId).build();
      storedExternal = Workflow.newExternalWorkflowStub(TargetWorkflow.class, exec);
      return "";
    }
  }

  @WorkflowInterface
  public interface UseExternalWorkflow {
    @WorkflowMethod(name = "UseExternal")
    String execute(String input);
  }

  public static class UseExternalWorkflowImpl implements UseExternalWorkflow {
    @Override
    public String execute(String input) {
      storedExternal.unblock();
      return "";
    }
  }

  @WorkflowInterface
  public interface StoreNexusServiceWorkflow {
    @WorkflowMethod(name = "StoreNexus")
    String execute(String input);
  }

  public static class StoreNexusServiceWorkflowImpl implements StoreNexusServiceWorkflow {
    @Override
    public String execute(String input) {
      storedNexus = Workflow.newNexusServiceStub(TestNexusServices.TestNexusService1.class);
      return "";
    }
  }

  @WorkflowInterface
  public interface UseNexusServiceWorkflow {
    @WorkflowMethod(name = "UseNexus")
    String execute(String input);
  }

  public static class UseNexusServiceWorkflowImpl implements UseNexusServiceWorkflow {
    @Override
    public String execute(String input) {
      return storedNexus.operation(input);
    }
  }

  @WorkflowInterface
  public interface SimpleChildWorkflow {
    @WorkflowMethod
    String run(String input);
  }

  public static class SimpleChildWorkflowImpl implements SimpleChildWorkflow {
    @Override
    public String run(String input) {
      return input;
    }
  }

  @WorkflowInterface
  public interface TargetWorkflow {
    @WorkflowMethod
    String execute();

    @SignalMethod
    void unblock();
  }

  public static class TargetWorkflowImpl implements TargetWorkflow {
    private final CompletablePromise<Void> done = Workflow.newPromise();

    @Override
    public String execute() {
      Workflow.await(done::isCompleted);
      return "";
    }

    @Override
    public void unblock() {
      done.complete(null);
    }
  }

  public static class SimpleActivityImpl implements TestActivities.TestActivity1 {
    @Override
    public String execute(String input) {
      return input;
    }
  }

  @ServiceImpl(service = TestNexusServices.TestNexusService1.class)
  public static class TestNexusServiceImpl {
    @OperationImpl
    public OperationHandler<String, String> operation() {
      return OperationHandler.sync((ctx, details, input) -> input);
    }
  }
}
