package io.temporal.worker;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.tuning.*;
import io.temporal.workflow.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;

public class ResourceBasedTunerTests {
  ResourceController<JVMSystemResourceInfo> resourceController =
      ResourceController.newSystemInfoController(
          ResourceBasedControllerOptions.newBuilder(0.5, 0.5).build());

  SlotSupplier<WorkflowSlotInfo> workflowTaskSlotSupplier =
      new ResourceBasedSlotsForType<>(
          resourceController, new ResourceBasedSlotOptions(2, 500, Duration.ofSeconds(0)));
  SlotSupplier<ActivitySlotInfo> activityTaskSlotSupplier =
      new ResourceBasedSlotsForType<>(
          resourceController, new ResourceBasedSlotOptions(1, 1000, Duration.ofMillis(50)));
  SlotSupplier<LocalActivitySlotInfo> localActivitySlotSupplier =
      new ResourceBasedSlotsForType<>(
          resourceController, new ResourceBasedSlotOptions(1, 1000, Duration.ofMillis(50)));

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkerOptions(
              WorkerOptions.newBuilder()
                  .setWorkerTuner(
                      new TunerHolder(
                          workflowTaskSlotSupplier,
                          activityTaskSlotSupplier,
                          localActivitySlotSupplier))
                  .build())
          .setActivityImplementations(new ActivitiesImpl())
          .setWorkflowTypes(ResourceTunerWorkflowImpl.class)
          .build();

  @Test
  public void canRunWithResourceBasedTuner() {
    ResourceTunerWorkflow workflow = testWorkflowRule.newWorkflowStub(ResourceTunerWorkflow.class);
    String result = workflow.execute();
  }

  @WorkflowInterface
  public interface ResourceTunerWorkflow {

    @WorkflowMethod
    String execute();
  }

  public static class ResourceTunerWorkflowImpl implements ResourceTunerWorkflow {

    @Override
    public String execute() {
      SleepActivity activity =
          Workflow.newActivityStub(
              SleepActivity.class,
              ActivityOptions.newBuilder()
                  .setScheduleToStartTimeout(Duration.ofMinutes(1))
                  .setStartToCloseTimeout(Duration.ofMinutes(1))
                  .setHeartbeatTimeout(Duration.ofSeconds(20))
                  .build());

      List<Promise<Void>> promises = new ArrayList<>();
      // Run some activities concurrently
      for (int j = 0; j < 10; j++) {
        Promise<Void> promise = Async.procedure(activity::sleep);
        promises.add(promise);
      }

      for (Promise<Void> promise : promises) {
        promise.get();
      }

      return "I'm done";
    }
  }

  @ActivityInterface
  public interface SleepActivity {
    void sleep();
  }

  public static class ActivitiesImpl implements SleepActivity {
    @Override
    public void sleep() {}
  }
}
