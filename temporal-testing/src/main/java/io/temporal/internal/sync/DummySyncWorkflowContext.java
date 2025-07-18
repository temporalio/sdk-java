package io.temporal.internal.sync;

import com.uber.m3.tally.NoopScope;
import com.uber.m3.tally.Scope;
import io.temporal.api.command.v1.ContinueAsNewWorkflowExecutionCommandAttributes;
import io.temporal.api.command.v1.SignalExternalWorkflowExecutionCommandAttributes;
import io.temporal.api.common.v1.*;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.sdk.v1.UserMetadata;
import io.temporal.common.RetryOptions;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.failure.CanceledFailure;
import io.temporal.internal.common.SdkFlag;
import io.temporal.internal.replay.ReplayWorkflowContext;
import io.temporal.internal.statemachines.*;
import io.temporal.workflow.Functions;
import java.time.Duration;
import java.util.*;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class DummySyncWorkflowContext {
  public static SyncWorkflowContext newDummySyncWorkflowContext() {
    SyncWorkflowContext context =
        new SyncWorkflowContext(
            "dummy",
            WorkflowExecution.newBuilder().setWorkflowId("dummy").setRunId("dummy").build(),
            null,
            new SignalDispatcher(DefaultDataConverter.STANDARD_INSTANCE),
            new QueryDispatcher(DefaultDataConverter.STANDARD_INSTANCE),
            new UpdateDispatcher(DefaultDataConverter.STANDARD_INSTANCE),
            null,
            DefaultDataConverter.STANDARD_INSTANCE,
            null);
    context.setReplayContext(new DummyReplayWorkflowContext());
    context.initHeadOutboundCallsInterceptor(context);
    context.initHeadInboundCallsInterceptor(
        new BaseRootWorkflowInboundCallsInterceptor(context) {
          @Override
          public WorkflowOutput execute(WorkflowInput input) {
            throw new UnsupportedOperationException(
                "#execute is not implemented or needed for low level DeterministicRunner tests");
          }
        });
    return context;
  }

  private static final class DummyReplayWorkflowContext implements ReplayWorkflowContext {

    private final Timer timer = new Timer();

    @Override
    public WorkflowExecution getWorkflowExecution() {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public WorkflowExecution getParentWorkflowExecution() {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public WorkflowExecution getRootWorkflowExecution() {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public WorkflowType getWorkflowType() {
      return WorkflowType.newBuilder().setName("dummy-workflow").build();
    }

    @Override
    public boolean isCancelRequested() {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public void setCancelRequested() {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public boolean isWorkflowMethodCompleted() {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public void setWorkflowMethodCompleted() {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public ContinueAsNewWorkflowExecutionCommandAttributes getContinueAsNewOnCompletion() {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public String getTaskQueue() {
      return "dummy-task-queue";
    }

    @Nullable
    @Override
    public RetryOptions getRetryOptions() {
      return null;
    }

    @Override
    public String getNamespace() {
      return "dummy-namespace";
    }

    @Override
    public String getWorkflowId() {
      return "dummy-workflow-id";
    }

    @Nonnull
    @Override
    public String getRunId() {
      return "dummy-run-id";
    }

    @Nonnull
    @Override
    public String getFirstExecutionRunId() {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public Optional<String> getContinuedExecutionRunId() {
      throw new UnsupportedOperationException("not implemented");
    }

    @Nonnull
    @Override
    public String getOriginalExecutionRunId() {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public Duration getWorkflowRunTimeout() {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public Duration getWorkflowExecutionTimeout() {
      return Duration.ZERO;
    }

    @Override
    public long getRunStartedTimestampMillis() {
      return 0;
    }

    @Nonnull
    @Override
    public Duration getWorkflowTaskTimeout() {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public Payload getMemo(String key) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    @Nullable
    public SearchAttributes getSearchAttributes() {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public ScheduleActivityTaskOutput scheduleActivityTask(
        ExecuteActivityParameters parameters,
        Functions.Proc2<Optional<Payloads>, Failure> callback) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public Functions.Proc scheduleLocalActivityTask(
        ExecuteLocalActivityParameters parameters, LocalActivityCallback callback) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public Functions.Proc1<Exception> startChildWorkflow(
        StartChildWorkflowExecutionParameters parameters,
        Functions.Proc2<WorkflowExecution, Exception> executionCallback,
        Functions.Proc2<Optional<Payloads>, Exception> callback) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public Functions.Proc1<Exception> startNexusOperation(
        StartNexusOperationParameters parameters,
        Functions.Proc2<Optional<String>, Failure> startedCallback,
        Functions.Proc2<Optional<Payload>, Failure> completionCallback) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public Functions.Proc1<Exception> signalExternalWorkflowExecution(
        SignalExternalWorkflowExecutionCommandAttributes.Builder attributes,
        Functions.Proc2<Void, Failure> callback) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public void requestCancelExternalWorkflowExecution(
        WorkflowExecution execution, Functions.Proc2<Void, RuntimeException> callback) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public void continueAsNewOnCompletion(
        ContinueAsNewWorkflowExecutionCommandAttributes attributes) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public Throwable getWorkflowTaskFailure() {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public void failWorkflowTask(Throwable failure) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public long currentTimeMillis() {
      return System.currentTimeMillis();
    }

    @Override
    public Functions.Proc1<RuntimeException> newTimer(
        Duration delay, UserMetadata metadata, Functions.Proc1<RuntimeException> callback) {
      timer.schedule(
          new TimerTask() {
            @Override
            public void run() {
              callback.apply(null);
            }
          },
          delay.toMillis());
      return (e) -> {
        callback.apply(new CanceledFailure(null));
      };
    }

    @Override
    public void sideEffect(
        Functions.Func<Optional<Payloads>> func, Functions.Proc1<Optional<Payloads>> callback) {
      callback.apply(func.apply());
    }

    @Override
    public void mutableSideEffect(
        String id,
        Functions.Func1<Optional<Payloads>, Optional<Payloads>> func,
        Functions.Proc1<Optional<Payloads>> callback) {
      callback.apply(func.apply(Optional.empty()));
    }

    @Override
    public boolean isReplaying() {
      return false;
    }

    @Override
    public Integer getVersion(
        String changeId,
        int minSupported,
        int maxSupported,
        Functions.Proc2<Integer, RuntimeException> callback) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public Random newRandom() {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public Scope getMetricsScope() {
      return new NoopScope();
    }

    @Override
    public boolean getEnableLoggingInReplay() {
      return false;
    }

    @Override
    public UUID randomUUID() {
      return UUID.randomUUID();
    }

    @Override
    public void upsertSearchAttributes(@Nonnull SearchAttributes searchAttributes) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public void upsertMemo(Memo memo) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public boolean tryUseSdkFlag(SdkFlag flag) {
      return false;
    }

    @Override
    public boolean checkSdkFlag(SdkFlag flag) {
      return false;
    }

    @Override
    public Optional<String> getCurrentBuildId() {
      return Optional.empty();
    }

    @Override
    public Priority getPriority() {
      return null;
    }

    @Override
    public int getAttempt() {
      return 1;
    }

    @Override
    public String getCronSchedule() {
      return "dummy-cron-schedule";
    }

    @Nullable
    @Override
    public Payloads getLastCompletionResult() {
      return null;
    }

    @Nullable
    @Override
    public Failure getPreviousRunFailure() {
      return null;
    }

    @Nullable
    @Override
    public String getFullReplayDirectQueryName() {
      return null;
    }

    @Override
    public Map<String, Payload> getHeader() {
      return null;
    }

    @Override
    public long getLastWorkflowTaskStartedEventId() {
      return 0;
    }

    @Override
    public long getHistorySize() {
      return 0;
    }

    @Override
    public boolean isContinueAsNewSuggested() {
      return false;
    }
  }
}
