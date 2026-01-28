package io.temporal.internal.sync;

import com.google.common.base.Defaults;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.common.interceptors.Header;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor.ChildWorkflowOutput;
import io.temporal.failure.TemporalFailure;
import io.temporal.workflow.*;
import java.lang.reflect.Type;
import java.util.Objects;

class ChildWorkflowStubImpl implements ChildWorkflowStub {

  private final String workflowType;
  private final ChildWorkflowOptions options;
  private final WorkflowOutboundCallsInterceptor outboundCallsInterceptor;
  private final CompletablePromise<WorkflowExecution> execution;
  private final Functions.Proc1<String> assertReadOnly;

  ChildWorkflowStubImpl(
      String workflowType,
      ChildWorkflowOptions options,
      WorkflowOutboundCallsInterceptor outboundCallsInterceptor,
      Functions.Proc1<String> assertReadOnly) {
    this.workflowType = Objects.requireNonNull(workflowType);
    this.options = ChildWorkflowOptions.newBuilder(options).validateAndBuildWithDefaults();
    this.outboundCallsInterceptor = Objects.requireNonNull(outboundCallsInterceptor);
    this.execution = Workflow.newPromise();
    // We register an empty handler to make sure that this promise is always "accessed" and never
    // leads to a log about it being completed exceptionally and non-accessed.
    // The "main" Child Workflow promise is the one returned from the execute method and that
    // promise will always be logged if not accessed.
    this.execution.handle((ex, failure) -> null);
    this.assertReadOnly = assertReadOnly;
  }

  @Override
  public String getWorkflowType() {
    return workflowType;
  }

  @Override
  public Promise<WorkflowExecution> getExecution() {
    // We create a new Promise here because we want it to be registered with the Runner
    CompletablePromise<WorkflowExecution> result = Workflow.newPromise();
    result.completeFrom(this.execution);
    return result;
  }

  @Override
  public ChildWorkflowOptions getOptions() {
    return options;
  }

  @Override
  public <R> R execute(Class<R> resultClass, Object... args) {
    return execute(resultClass, resultClass, args);
  }

  @Override
  public <R> R execute(Class<R> resultClass, Type resultType, Object... args) {
    assertReadOnly.apply("schedule child workflow");
    Promise<R> result = executeAsync(resultClass, resultType, args);
    if (AsyncInternal.isAsync()) {
      AsyncInternal.setAsyncResult(result);
      return Defaults.defaultValue(resultClass);
    }
    try {
      return result.get();
    } catch (TemporalFailure e) {
      // Reset stack to the current one. Otherwise it is very confusing to see a stack of
      // an event handling method.
      e.setStackTrace(Thread.currentThread().getStackTrace());
      throw e;
    }
  }

  @Override
  public <R> Promise<R> executeAsync(Class<R> resultClass, Object... args) {
    return executeAsync(resultClass, resultClass, args);
  }

  @Override
  public <R> Promise<R> executeAsync(Class<R> resultClass, Type resultType, Object... args) {
    assertReadOnly.apply("schedule child workflow");
    ChildWorkflowOutput<R> result =
        outboundCallsInterceptor.executeChildWorkflow(
            new WorkflowOutboundCallsInterceptor.ChildWorkflowInput<>(
                getWorkflowIdForStart(options),
                workflowType,
                resultClass,
                resultType,
                args,
                options,
                Header.empty()));
    execution.completeFrom(result.getWorkflowExecution());
    return result.getResult();
  }

  @Override
  public void signal(String signalName, Object... args) {
    assertReadOnly.apply("signal workflow");
    Promise<Void> signaled =
        outboundCallsInterceptor
            .signalExternalWorkflow(
                new WorkflowOutboundCallsInterceptor.SignalExternalInput(
                    execution.get(), signalName, Header.empty(), args))
            .getResult();
    if (AsyncInternal.isAsync()) {
      AsyncInternal.setAsyncResult(signaled);
      return;
    }
    try {
      signaled.get();
    } catch (SignalExternalWorkflowException e) {
      // Reset stack to the current one. Otherwise it is very confusing to see a stack of
      // an event handling method.
      e.setStackTrace(Thread.currentThread().getStackTrace());
      throw e;
    }
  }

  private String getWorkflowIdForStart(ChildWorkflowOptions options) {
    String workflowId = options.getWorkflowId();
    if (workflowId == null) {
      workflowId = Workflow.randomUUID().toString();
    }
    return workflowId;
  }
}
