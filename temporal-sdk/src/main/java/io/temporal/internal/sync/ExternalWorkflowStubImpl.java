package io.temporal.internal.sync;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.common.interceptors.Header;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;
import io.temporal.workflow.*;
import java.util.Objects;
import javax.annotation.Nullable;

/** Dynamic implementation of a strongly typed child workflow interface. */
class ExternalWorkflowStubImpl implements ExternalWorkflowStub {

  private final WorkflowOutboundCallsInterceptor outboundCallsInterceptor;
  private final WorkflowExecution execution;
  private Functions.Proc1<String> assertReadOnly;

  public ExternalWorkflowStubImpl(
      WorkflowExecution execution,
      WorkflowOutboundCallsInterceptor outboundCallsInterceptor,
      Functions.Proc1<String> assertReadOnly) {
    this.outboundCallsInterceptor = Objects.requireNonNull(outboundCallsInterceptor);
    this.execution = Objects.requireNonNull(execution);
    this.assertReadOnly = assertReadOnly;
  }

  @Override
  public WorkflowExecution getExecution() {
    return execution;
  }

  @Override
  public void signal(String signalName, Object... args) {
    signalWithOptions(signalName, SignalExternalWorkflowOptions.getDefaultInstance(), args);
  }

  @Override
  public void signalWithOptions(
      String signalName, SignalExternalWorkflowOptions options, Object... args) {
    assertReadOnly.apply("signal external workflow");
    Promise<Void> signaled =
        outboundCallsInterceptor
            .signalExternalWorkflow(
                new WorkflowOutboundCallsInterceptor.SignalExternalInput(
                    execution, signalName, Header.empty(), args, options))
            .getResult();
    if (AsyncInternal.isAsync()) {
      AsyncInternal.setAsyncResult(signaled);
      return;
    }
    try {
      signaled.get();
    } catch (SignalExternalWorkflowException e) {
      // Reset stack to the current one. Otherwise, it is very confusing to see a stack of
      // an event handling method.
      e.setStackTrace(Thread.currentThread().getStackTrace());
      throw e;
    }
  }

  @Override
  public void cancel() {
    cancelWithOptions(CancelExternalWorkflowOptions.getDefaultInstance());
  }

  @Override
  public void cancel(@Nullable String reason) {
    cancelWithOptions(CancelExternalWorkflowOptions.newBuilder().setReason(reason).build());
  }

  @Override
  public void cancelWithOptions(CancelExternalWorkflowOptions options) {
    assertReadOnly.apply("cancel external workflow");
    if (options.getReason() == null) {
      try {
        CancellationScope currentScope = CancellationScope.current();
        if (currentScope.isCancelRequested()) {
          options = options.toBuilder().setReason(currentScope.getCancellationReason()).build();
        }
      } catch (IllegalStateException ignored) {
        // Outside of workflow thread; leave reason as null.
      }
    }
    Promise<Void> cancelRequested =
        outboundCallsInterceptor
            .cancelWorkflow(
                new WorkflowOutboundCallsInterceptor.CancelWorkflowInput(execution, options))
            .getResult();
    if (AsyncInternal.isAsync()) {
      AsyncInternal.setAsyncResult(cancelRequested);
      return;
    }
    try {
      cancelRequested.get();
    } catch (CancelExternalWorkflowException e) {
      // Reset stack to the current one. Otherwise it is very confusing to see a stack of
      // an event handling method.
      e.setStackTrace(Thread.currentThread().getStackTrace());
      throw e;
    }
  }
}
