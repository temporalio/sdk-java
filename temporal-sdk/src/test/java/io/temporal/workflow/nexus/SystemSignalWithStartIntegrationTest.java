package io.temporal.workflow.nexus;

import static org.junit.Assert.assertEquals;

import io.temporal.api.enums.v1.WorkflowIdConflictPolicy;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.testing.CloudTestExclusion.RequiresCloudProvisioning;
import io.temporal.testing.CloudTestExclusionNote;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.*;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;

@CloudTestExclusionNote("Requires a server supporting the Temporal system Nexus workflow service.")
@Category(RequiresCloudProvisioning.class)
public class SystemSignalWithStartIntegrationTest {
  @Rule
  public SDKTestWorkflowRule rule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(CallerImpl.class, TargetImpl.class).build();

  @Before
  public void requireSystemService() {
    Assume.assumeTrue(
        "System Nexus requires a supporting external server",
        SDKTestWorkflowRule.useExternalService);
  }

  @Test
  public void startsThenSignalsTheExistingExecutionAndReplays() throws Exception {
    Caller caller = rule.newWorkflowStubTimeoutOptions(Caller.class);
    String target = caller.run();
    assertEquals(
        "start:first:second",
        rule.getWorkflowClient().newUntypedWorkflowStub(target).getResult(String.class));
    String callerId =
        io.temporal.client.WorkflowStub.fromTyped(caller).getExecution().getWorkflowId();
    WorkflowReplayer.replayWorkflowExecution(rule.getExecutionHistory(callerId), rule.getWorker());
  }

  @WorkflowInterface
  public interface Target {
    @WorkflowMethod(name = "SystemSignalTarget")
    String run(String initial);

    @SignalMethod
    void notifySignal(String value);
  }

  public static class TargetImpl implements Target {
    private final java.util.List<String> signals = new java.util.ArrayList<>();

    @Override
    public String run(String initial) {
      Workflow.await(() -> signals.size() == 2);
      return initial + ":" + String.join(":", signals);
    }

    @Override
    public void notifySignal(String value) {
      signals.add(value);
    }
  }

  @WorkflowInterface
  public interface Caller {
    @WorkflowMethod
    String run();
  }

  public static class CallerImpl implements Caller {
    @Override
    public String run() {
      String id = Workflow.getInfo().getWorkflowId() + "-target";
      SignalWithStartWorkflowRequest request =
          SignalWithStartWorkflowRequest.newBuilder()
              .setWorkflow("SystemSignalTarget")
              .setSignal("notifySignal")
              .setId(id)
              .setTaskQueue(Workflow.getInfo().getTaskQueue())
              .setIdConflictPolicy(
                  WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING)
              .setArgs(DefaultDataConverter.STANDARD_INSTANCE.toPayloads("start").get())
              .setSignalArgs(DefaultDataConverter.STANDARD_INSTANCE.toPayloads("first").get())
              .build();
      ExternalWorkflowStub external = Workflow.signalWithStartWorkflow(request);
      assertEquals(id, external.getExecution().getWorkflowId());
      Async.function(
              Workflow::signalWithStartWorkflow,
              request.toBuilder()
                  .setArgs(DefaultDataConverter.STANDARD_INSTANCE.toPayloads("ignored").get())
                  .setSignalArgs(DefaultDataConverter.STANDARD_INSTANCE.toPayloads("second").get())
                  .build())
          .get();
      return id;
    }
  }
}
