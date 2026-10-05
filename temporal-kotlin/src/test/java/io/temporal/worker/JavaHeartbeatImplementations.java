package io.temporal.worker;

import io.temporal.activity.ActivityInterface;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/** Java implementations without Kotlin metadata, even when Kotlin is on the classpath. */
public final class JavaHeartbeatImplementations {
  private JavaHeartbeatImplementations() {}

  @WorkflowInterface
  public interface JavaWorkflow {
    @WorkflowMethod
    void execute();
  }

  public static class JavaWorkflowImpl implements JavaWorkflow {
    @Override
    public void execute() {}
  }

  @ActivityInterface
  public interface JavaActivity {
    void executeActivity();
  }

  public static class JavaActivityImpl implements JavaActivity {
    @Override
    public void executeActivity() {}
  }
}
