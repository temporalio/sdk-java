package io.temporal.internal.sync;

import com.google.common.base.Defaults;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;
import io.temporal.failure.TemporalFailure;
import io.temporal.workflow.*;
import java.lang.reflect.Type;
import java.util.Collections;

public class NexusServiceStubImpl implements NexusServiceStub {
  private final String name;
  private final NexusServiceOptions options;
  private final WorkflowOutboundCallsInterceptor outboundCallsInterceptor;
  private final Functions.Proc1<String> assertReadOnly;

  private void assertSameWorkflow() {
    if (outboundCallsInterceptor != WorkflowInternal.getWorkflowOutboundInterceptor()) {
      throw new IllegalStateException(
          "Nexus service stub belongs to a different workflow. Create a new stub for each workflow instance.");
    }
  }

  public NexusServiceStubImpl(
      String name,
      NexusServiceOptions options,
      WorkflowOutboundCallsInterceptor outboundCallsInterceptor,
      Functions.Proc1<String> assertReadOnly) {
    this.name = name;
    this.options = options;
    this.outboundCallsInterceptor = outboundCallsInterceptor;
    this.assertReadOnly = assertReadOnly;
  }

  @Override
  public <R> R execute(String operationName, Class<R> resultClass, Object arg) {
    return execute(operationName, resultClass, resultClass, arg);
  }

  @Override
  public <R> R execute(String operationName, Class<R> resultClass, Type resultType, Object arg) {
    assertSameWorkflow();
    assertReadOnly.apply("execute nexus operation");
    Promise<R> result = executeAsync(operationName, resultClass, resultType, arg);
    if (AsyncInternal.isAsync()) {
      AsyncInternal.setAsyncResult(result);
      return Defaults.defaultValue(resultClass);
    }
    try {
      return result.get();
    } catch (TemporalFailure e) {
      // Reset stack to the current one. Otherwise, it is very confusing to see a stack of
      // an event handling method.
      e.setStackTrace(Thread.currentThread().getStackTrace());
      throw e;
    }
  }

  @Override
  public <R> Promise<R> executeAsync(String operationName, Class<R> resultClass, Object arg) {
    return executeAsync(operationName, resultClass, resultClass, arg);
  }

  @Override
  public <R> Promise<R> executeAsync(
      String operationName, Class<R> resultClass, Type resultType, Object arg) {
    assertSameWorkflow();
    assertReadOnly.apply("execute nexus operation");
    NexusOperationOptions mergedOptions =
        NexusOperationOptions.newBuilder(options.getOperationOptions())
            .mergeNexusOperationOptions(options.getOperationMethodOptions().get(operationName))
            .build();
    WorkflowOutboundCallsInterceptor.ExecuteNexusOperationOutput<R> result =
        outboundCallsInterceptor.executeNexusOperation(
            new WorkflowOutboundCallsInterceptor.ExecuteNexusOperationInput<>(
                options.getEndpoint(),
                name,
                operationName,
                resultClass,
                resultType,
                arg,
                mergedOptions,
                Collections.emptyMap()));
    return result.getResult();
  }

  @Override
  public <R> NexusOperationHandle<R> start(String operationName, Class<R> resultClass, Object arg) {
    return start(operationName, resultClass, resultClass, arg);
  }

  @Override
  public <R> NexusOperationHandle<R> start(
      String operationName, Class<R> resultClass, Type resultType, Object arg) {
    assertSameWorkflow();
    assertReadOnly.apply("schedule nexus operation");
    NexusOperationOptions mergedOptions =
        NexusOperationOptions.newBuilder(options.getOperationOptions())
            .mergeNexusOperationOptions(options.getOperationMethodOptions().get(operationName))
            .build();
    WorkflowOutboundCallsInterceptor.ExecuteNexusOperationOutput<R> result =
        outboundCallsInterceptor.executeNexusOperation(
            new WorkflowOutboundCallsInterceptor.ExecuteNexusOperationInput<>(
                options.getEndpoint(),
                name,
                operationName,
                resultClass,
                resultType,
                arg,
                mergedOptions,
                Collections.emptyMap()));
    return new NexusOperationHandleImpl<>(result.getOperationExecution(), result.getResult());
  }

  @Override
  public NexusServiceOptions getOptions() {
    return options;
  }
}
