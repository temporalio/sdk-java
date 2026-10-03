package io.temporal.workflow.activityTests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.enums.v1.EventType;
import io.temporal.client.WorkflowStub;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.common.interceptors.WorkerInterceptorBase;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptor;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptorBase;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptorBase;
import io.temporal.failure.CanceledFailure;
import io.temporal.internal.common.SdkFlag;
import io.temporal.internal.statemachines.WorkflowStateMachines;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.workflow.Async;
import io.temporal.workflow.CancellationScope;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;

public class AsyncActivityBlockingInterceptorTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkerFactoryOptions(
              WorkerFactoryOptions.newBuilder()
                  .setWorkerInterceptors(new BlockingActivityInterceptor())
                  .build())
          .setWorkflowTypes(TestWorkflowImpl.class, CancelBeforeSchedulingWorkflowImpl.class)
          .setActivityImplementations(new TestActivityImpl())
          .build();

  @Test
  public void asyncActivityAllowsInterceptorToBlockBeforeAndAfterScheduling() {
    List<SdkFlag> savedInitialFlags = WorkflowStateMachines.initialFlags;
    List<SdkFlag> flags = new ArrayList<>(savedInitialFlags);
    flags.add(SdkFlag.SCHEDULE_ASYNC_STUB_OPERATIONS);
    WorkflowStateMachines.initialFlags = Collections.unmodifiableList(flags);
    try {
      TestWorkflow workflow = testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow.class);
      assertEquals("true:done:true", workflow.execute());
    } finally {
      WorkflowStateMachines.initialFlags = savedInitialFlags;
    }
  }

  @Test
  public void previousReleaseBlockingInterceptorHistoryReplays() throws Exception {
    // This history was recorded with Java SDK v1.39.0 and an interceptor that waits on both sides
    // of the activity scheduling call.
    WorkflowReplayer.replayWorkflowExecutionFromResource(
        "asyncActivityBlockingInterceptorV139.json", testWorkflowRule.getWorker());
  }

  @Test
  public void cancellationWhileInterceptorWaitsDoesNotScheduleActivity() {
    List<SdkFlag> savedInitialFlags = WorkflowStateMachines.initialFlags;
    List<SdkFlag> flags = new ArrayList<>(savedInitialFlags);
    flags.add(SdkFlag.SCHEDULE_ASYNC_STUB_OPERATIONS);
    WorkflowStateMachines.initialFlags = Collections.unmodifiableList(flags);
    try {
      CancelBeforeSchedulingWorkflow workflow =
          testWorkflowRule.newWorkflowStubTimeoutOptions(CancelBeforeSchedulingWorkflow.class);
      assertEquals("cancelled", workflow.execute());
      WorkflowStub stub = WorkflowStub.fromTyped(workflow);
      WorkflowExecutionHistory history =
          testWorkflowRule
              .getWorkflowClient()
              .fetchHistory(stub.getExecution().getWorkflowId(), stub.getExecution().getRunId());
      assertTrue(
          history.getEvents().stream()
                  .filter(event -> event.getEventType() == EventType.EVENT_TYPE_TIMER_STARTED)
                  .count()
              >= 2);
      assertEquals(
          0,
          history.getEvents().stream()
              .filter(event -> event.getEventType() == EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED)
              .count());
    } finally {
      WorkflowStateMachines.initialFlags = savedInitialFlags;
    }
  }

  @WorkflowInterface
  public interface TestWorkflow {
    @WorkflowMethod
    String execute();
  }

  @WorkflowInterface
  public interface CancelBeforeSchedulingWorkflow {
    @WorkflowMethod
    String execute();
  }

  @ActivityInterface
  public interface TestActivity {
    String call();
  }

  public static class TestWorkflowImpl implements TestWorkflow {
    private final TestActivity activity =
        Workflow.newActivityStub(
            TestActivity.class,
            ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build());

    @Override
    public String execute() {
      long start = Workflow.currentTimeMillis();
      Promise<String> result = Async.function(activity::call);
      boolean returnedImmediately = Workflow.currentTimeMillis() == start;
      String value = result.get();
      boolean interceptorFinishedWaiting = Workflow.currentTimeMillis() >= start + 2000;
      return returnedImmediately + ":" + value + ":" + interceptorFinishedWaiting;
    }
  }

  public static class TestActivityImpl implements TestActivity {
    @Override
    public String call() {
      return "done";
    }
  }

  public static class CancelBeforeSchedulingWorkflowImpl implements CancelBeforeSchedulingWorkflow {
    private final TestActivity activity =
        Workflow.newActivityStub(
            TestActivity.class,
            ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build());

    @Override
    public String execute() {
      AtomicReference<Promise<String>> result = new AtomicReference<>();
      CancellationScope scope =
          Workflow.newCancellationScope(() -> result.set(Async.function(activity::call)));
      scope.run();
      Workflow.sleep(Duration.ofMillis(100));
      scope.cancel();
      RuntimeException failure = result.get().getFailure();
      return failure instanceof CanceledFailure ? "cancelled" : failure.getClass().getName();
    }
  }

  private static class BlockingActivityInterceptor extends WorkerInterceptorBase {
    @Override
    public WorkflowInboundCallsInterceptor interceptWorkflow(WorkflowInboundCallsInterceptor next) {
      return new WorkflowInboundCallsInterceptorBase(next) {
        @Override
        public void init(WorkflowOutboundCallsInterceptor outboundCalls) {
          next.init(new BlockingOutboundCallsInterceptor(outboundCalls));
        }
      };
    }
  }

  private static class BlockingOutboundCallsInterceptor
      extends WorkflowOutboundCallsInterceptorBase {
    BlockingOutboundCallsInterceptor(WorkflowOutboundCallsInterceptor next) {
      super(next);
    }

    @Override
    public <R> ActivityOutput<R> executeActivity(ActivityInput<R> input) {
      Workflow.sleep(Duration.ofSeconds(1));
      ActivityOutput<R> output = super.executeActivity(input);
      Workflow.sleep(Duration.ofSeconds(1));
      return output;
    }
  }
}
