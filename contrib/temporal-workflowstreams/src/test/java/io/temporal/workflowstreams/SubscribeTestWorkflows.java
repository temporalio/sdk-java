package io.temporal.workflowstreams;

import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInit;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/** Host workflow fixture shared by the subscription tests. */
public final class SubscribeTestWorkflows {
  private SubscribeTestWorkflows() {}

  @WorkflowInterface
  public interface SubscribeHostWorkflow {
    @WorkflowMethod
    void execute(WorkflowStreamState priorState);

    @SignalMethod
    void finish();

    @SignalMethod
    void rollover();

    @SignalMethod
    void publishLocal(String topic, String value);

    @UpdateMethod
    void truncate(long upToOffset);
  }

  public static class SubscribeHostWorkflowImpl implements SubscribeHostWorkflow {
    private final WorkflowStream stream;
    private boolean finished;
    private boolean rollover;

    // Construct the stream in @WorkflowInit — the module's recommended pattern — so poll
    // updates arriving before the workflow method runs (a real-server race the in-process
    // test service never exhibits) are accepted rather than rejected with an unknown-update
    // error.
    @WorkflowInit
    public SubscribeHostWorkflowImpl(WorkflowStreamState priorState) {
      stream = WorkflowStream.newInstance(priorState);
    }

    @Override
    public void execute(WorkflowStreamState priorState) {
      Workflow.await(() -> finished || rollover);
      if (rollover) {
        stream.continueAsNew(state -> new Object[] {state});
      }
    }

    @Override
    public void finish() {
      finished = true;
    }

    @Override
    public void rollover() {
      rollover = true;
    }

    @Override
    public void publishLocal(String topic, String value) {
      stream.topic(topic).publish(value);
    }

    @Override
    public void truncate(long upToOffset) {
      stream.truncate(upToOffset);
    }
  }
}
