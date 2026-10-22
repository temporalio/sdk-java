package io.temporal.workflow;

import static org.junit.Assert.assertEquals;

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

public class AwaitConditionEvaluatedByInterceptorTest {
  private static volatile Boolean readOnlyInCondition;

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestWorkflowImpl.class)
          .setWorkerFactoryOptions(
              WorkerFactoryOptions.newBuilder()
                  .setWorkerInterceptors(new ConditionEvaluatingInterceptor())
                  .build())
          .build();

  /** An await condition evaluated by an interceptor is read-only too. */
  @Test
  public void conditionEvaluatedByInterceptorIsReadOnly() {
    testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow.class).run();

    assertEquals(Boolean.TRUE, readOnlyInCondition);
  }

  @WorkflowInterface
  public interface TestWorkflow {
    @WorkflowMethod
    void run();
  }

  public static class TestWorkflowImpl implements TestWorkflow {
    @Override
    public void run() {
      Workflow.await(
          () -> {
            readOnlyInCondition = WorkflowUnsafe.isReadOnly();
            return true;
          });
    }
  }

  private static class ConditionEvaluatingInterceptor extends WorkerInterceptorBase {
    @Override
    public WorkflowInboundCallsInterceptor interceptWorkflow(WorkflowInboundCallsInterceptor next) {
      return new WorkflowInboundCallsInterceptorBase(next) {
        @Override
        public void init(WorkflowOutboundCallsInterceptor outboundCalls) {
          super.init(
              new WorkflowOutboundCallsInterceptorBase(outboundCalls) {
                @Override
                public void await(String reason, Supplier<Boolean> unblockCondition) {
                  if (!unblockCondition.get()) {
                    super.await(reason, unblockCondition);
                  }
                }
              });
        }
      };
    }
  }
}
