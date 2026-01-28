package io.temporal.testserver.functional.common;

import io.temporal.workflow.*;

public class TestWorkflows {
  @WorkflowInterface
  public interface PrimitiveWorkflow {
    @WorkflowMethod
    void execute();
  }

  @WorkflowInterface
  public interface WorkflowTakesBool {
    @WorkflowMethod
    void execute(boolean arg);
  }

  @WorkflowInterface
  public interface WorkflowReturnsString {
    @WorkflowMethod
    String execute();
  }

  @WorkflowInterface
  public interface PrimitiveChildWorkflow {
    @WorkflowMethod
    void execute();
  }

  @WorkflowInterface
  public interface WorkflowWithSignal {
    @WorkflowMethod
    void execute();

    @SignalMethod
    void signal();
  }

  @WorkflowInterface
  public interface WorkflowWithUpdate {
    @WorkflowMethod
    void execute();

    @UpdateMethod
    void update(UpdateType type);

    @UpdateValidatorMethod(updateName = "update")
    void updateValidator(UpdateType type);

    @SignalMethod
    void signal();
  }

  public enum UpdateType {
    REJECT,
    COMPLETE,
    DELAYED_COMPLETE,
    BLOCK,
    FINISH_WORKFLOW,
  }

  @WorkflowInterface
  public interface PrimitiveNexusHandlerWorkflow {
    @WorkflowMethod
    Object execute(String input);
  }
}
