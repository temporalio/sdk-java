package io.temporal.workflow;

import static org.junit.Assert.assertEquals;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.common.interceptors.WorkerInterceptorBase;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptor;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptorBase;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptorBase;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.workflow.unsafe.WorkflowUnsafe;
import java.util.function.Supplier;
import org.junit.Rule;
import org.junit.Test;

public class AwaitConditionReadOnlyTest {
  private static final String STREAM_NAME = "io.temporal.test";

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestWorkflowImpl.class)
          .setWorkerFactoryOptions(
              WorkerFactoryOptions.newBuilder()
                  .setWorkerInterceptors(new ConditionWrappingInterceptor())
                  .build())
          .build();

  /**
   * An await condition, including interceptor code that wraps it, is evaluated a different number
   * of times live and on replay, so draws there must be prevented by read-only.
   */
  @Test
  public void awaitConditionAndInterceptorDrawsArePreventedByReadOnly() {
    TestWorkflow workflow = testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow.class);
    WorkflowExecution execution = WorkflowClient.start(workflow::run);
    // Signal in a later Workflow Task so the condition is evaluated across tasks.
    testWorkflowRule.waitForTheEndOfWFT(execution.getWorkflowId());
    workflow.finish();

    long live = WorkflowStub.fromTyped(workflow).getResult(Long.class);

    // The query replays the completed Workflow from the start.
    assertEquals(live, workflow.draw());
  }

  @WorkflowInterface
  public interface TestWorkflow {
    @WorkflowMethod
    long run();

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
      Workflow.await(
          () -> {
            drawIfNotReadOnly();
            return finished;
          });
      draw = Workflow.getRandomStream(STREAM_NAME).nextLong();
      return draw;
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

  private static void drawIfNotReadOnly() {
    if (!WorkflowUnsafe.isReadOnly()) {
      Workflow.getRandomStream(STREAM_NAME).nextLong();
    }
  }

  private static class ConditionWrappingInterceptor extends WorkerInterceptorBase {
    @Override
    public WorkflowInboundCallsInterceptor interceptWorkflow(WorkflowInboundCallsInterceptor next) {
      return new WorkflowInboundCallsInterceptorBase(next) {
        @Override
        public void init(WorkflowOutboundCallsInterceptor outboundCalls) {
          super.init(
              new WorkflowOutboundCallsInterceptorBase(outboundCalls) {
                @Override
                public void await(String reason, Supplier<Boolean> unblockCondition) {
                  super.await(
                      reason,
                      () -> {
                        drawIfNotReadOnly();
                        return unblockCondition.get();
                      });
                }
              });
        }
      };
    }
  }
}
