package io.temporal.workflow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.client.WorkflowUpdateException;
import io.temporal.common.context.ContextPropagator;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.unsafe.WorkflowUnsafe;
import java.util.Collections;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;

public class UpdateThreadStartReadOnlyTest {
  private static final String STREAM_NAME = "io.temporal.test";

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestWorkflowImpl.class)
          .setWorkflowClientOptions(
              WorkflowClientOptions.newBuilder()
                  .setContextPropagators(Collections.singletonList(new DrawingPropagator()))
                  .build())
          .build();

  /**
   * The thread for a rejected Update only exists during live execution, so context propagation at
   * thread start must be read-only to prevent draws that replay would not repeat.
   */
  @Test
  public void contextPropagationForRejectedUpdateIsReadOnly() {
    TestWorkflow workflow = testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow.class);
    WorkflowExecution execution = WorkflowClient.start(workflow::run);
    testWorkflowRule.waitForTheEndOfWFT(execution.getWorkflowId());
    assertThrows(WorkflowUpdateException.class, workflow::update);
    workflow.finish();

    long live = WorkflowStub.fromTyped(workflow).getResult(Long.class);

    // The query replays the completed Workflow from the start.
    assertEquals(live, workflow.draw());
  }

  @WorkflowInterface
  public interface TestWorkflow {
    @WorkflowMethod
    long run();

    @UpdateMethod
    void update();

    @UpdateValidatorMethod(updateName = "update")
    void validateUpdate();

    @SignalMethod
    void finish();

    @QueryMethod
    long draw();
  }

  public static class TestWorkflowImpl implements TestWorkflow {
    private boolean finished;
    private long draw;

    @Override
    public long run() {
      Workflow.await(() -> finished);
      draw = Workflow.getRandomStream(STREAM_NAME).nextLong();
      return draw;
    }

    @Override
    public void update() {}

    @Override
    public void validateUpdate() {
      throw new IllegalArgumentException("rejected");
    }

    @Override
    public void finish() {
      finished = true;
    }

    @Override
    public long draw() {
      return draw;
    }
  }

  /** Draws from the random stream whenever it sets the context, unless read-only. */
  private static class DrawingPropagator implements ContextPropagator {
    @Override
    public String getName() {
      return "drawing";
    }

    @Override
    public Map<String, Payload> serializeContext(Object context) {
      return Collections.singletonMap(
          "context", DefaultDataConverter.STANDARD_INSTANCE.toPayload(context).get());
    }

    @Override
    public Object deserializeContext(Map<String, Payload> context) {
      return DefaultDataConverter.STANDARD_INSTANCE.fromPayload(
          context.get("context"), String.class, String.class);
    }

    @Override
    public Object getCurrentContext() {
      return "context";
    }

    @Override
    public void setCurrentContext(Object context) {
      if (!WorkflowUnsafe.isReadOnly()) {
        Workflow.getRandomStream(STREAM_NAME).nextLong();
      }
    }
  }
}
