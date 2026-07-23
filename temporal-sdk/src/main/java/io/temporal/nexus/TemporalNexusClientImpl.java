package io.temporal.nexus;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.nexusrpc.handler.HandlerException;
import io.nexusrpc.handler.OperationContext;
import io.nexusrpc.handler.OperationStartDetails;
import io.temporal.client.ActivityClient;
import io.temporal.client.ActivityClientOptions;
import io.temporal.client.StartActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.Experimental;
import io.temporal.common.interceptors.ActivityClientCallsInterceptor;
import io.temporal.common.interceptors.Header;
import io.temporal.internal.client.ActivityClientInternal;
import io.temporal.internal.client.NexusStartActivityResponse;
import io.temporal.internal.client.NexusStartWorkflowResponse;
import io.temporal.internal.nexus.CurrentNexusOperationContext;
import io.temporal.internal.nexus.InternalNexusOperationContext;
import io.temporal.internal.nexus.NexusOperationMetadata;
import io.temporal.internal.nexus.NexusStartActivityHelper;
import io.temporal.internal.nexus.NexusStartWorkflowHelper;
import io.temporal.internal.nexus.OperationTokenUtil;
import io.temporal.internal.util.MethodExtractor;
import io.temporal.workflow.Functions;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Package-private implementation of {@link TemporalNexusClient}. */
@Experimental
final class TemporalNexusClientImpl implements TemporalNexusClient {

  private final WorkflowClient client;
  private final OperationContext operationContext;
  private final OperationStartDetails operationStartDetails;
  private final AtomicBoolean asyncOperationStarted = new AtomicBoolean(false);

  TemporalNexusClientImpl(
      WorkflowClient client,
      OperationContext operationContext,
      OperationStartDetails operationStartDetails) {
    this.client = Objects.requireNonNull(client);
    this.operationContext = Objects.requireNonNull(operationContext);
    this.operationStartDetails = Objects.requireNonNull(operationStartDetails);
  }

  @Override
  public WorkflowClient getWorkflowClient() {
    return client;
  }

  // ---------- Returning (Func) overloads ----------

  @Override
  public <T, R> TemporalOperationResult<R> startWorkflow(
      Class<T> workflowClass, Functions.Func1<T, R> workflowMethod, WorkflowOptions options) {
    T stub = client.newWorkflowStub(workflowClass, options);
    return invokeAndReturn(WorkflowHandle.fromWorkflowMethod(() -> workflowMethod.apply(stub)));
  }

  @Override
  public <T, A1, R> TemporalOperationResult<R> startWorkflow(
      Class<T> workflowClass,
      Functions.Func2<T, A1, R> workflowMethod,
      A1 arg1,
      WorkflowOptions options) {
    T stub = client.newWorkflowStub(workflowClass, options);
    return invokeAndReturn(
        WorkflowHandle.fromWorkflowMethod(() -> workflowMethod.apply(stub, arg1)));
  }

  @Override
  public <T, A1, A2, R> TemporalOperationResult<R> startWorkflow(
      Class<T> workflowClass,
      Functions.Func3<T, A1, A2, R> workflowMethod,
      A1 arg1,
      A2 arg2,
      WorkflowOptions options) {
    T stub = client.newWorkflowStub(workflowClass, options);
    return invokeAndReturn(
        WorkflowHandle.fromWorkflowMethod(() -> workflowMethod.apply(stub, arg1, arg2)));
  }

  @Override
  public <T, A1, A2, A3, R> TemporalOperationResult<R> startWorkflow(
      Class<T> workflowClass,
      Functions.Func4<T, A1, A2, A3, R> workflowMethod,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      WorkflowOptions options) {
    T stub = client.newWorkflowStub(workflowClass, options);
    return invokeAndReturn(
        WorkflowHandle.fromWorkflowMethod(() -> workflowMethod.apply(stub, arg1, arg2, arg3)));
  }

  @Override
  public <T, A1, A2, A3, A4, R> TemporalOperationResult<R> startWorkflow(
      Class<T> workflowClass,
      Functions.Func5<T, A1, A2, A3, A4, R> workflowMethod,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      WorkflowOptions options) {
    T stub = client.newWorkflowStub(workflowClass, options);
    return invokeAndReturn(
        WorkflowHandle.fromWorkflowMethod(
            () -> workflowMethod.apply(stub, arg1, arg2, arg3, arg4)));
  }

  @Override
  public <T, A1, A2, A3, A4, A5, R> TemporalOperationResult<R> startWorkflow(
      Class<T> workflowClass,
      Functions.Func6<T, A1, A2, A3, A4, A5, R> workflowMethod,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      WorkflowOptions options) {
    T stub = client.newWorkflowStub(workflowClass, options);
    return invokeAndReturn(
        WorkflowHandle.fromWorkflowMethod(
            () -> workflowMethod.apply(stub, arg1, arg2, arg3, arg4, arg5)));
  }

  @Override
  public <T, A1, A2, A3, A4, A5, A6, R> TemporalOperationResult<R> startWorkflow(
      Class<T> workflowClass,
      Functions.Func7<T, A1, A2, A3, A4, A5, A6, R> workflowMethod,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6,
      WorkflowOptions options) {
    T stub = client.newWorkflowStub(workflowClass, options);
    return invokeAndReturn(
        WorkflowHandle.fromWorkflowMethod(
            () -> workflowMethod.apply(stub, arg1, arg2, arg3, arg4, arg5, arg6)));
  }

  // ---------- Void (Proc) overloads ----------

  @Override
  public <T> TemporalOperationResult<Void> startWorkflow(
      Class<T> workflowClass, Functions.Proc1<T> workflowMethod, WorkflowOptions options) {
    T stub = client.newWorkflowStub(workflowClass, options);
    return invokeAndReturn(WorkflowHandle.fromWorkflowMethod(() -> workflowMethod.apply(stub)));
  }

  @Override
  public <T, A1> TemporalOperationResult<Void> startWorkflow(
      Class<T> workflowClass,
      Functions.Proc2<T, A1> workflowMethod,
      A1 arg1,
      WorkflowOptions options) {
    T stub = client.newWorkflowStub(workflowClass, options);
    return invokeAndReturn(
        WorkflowHandle.fromWorkflowMethod(() -> workflowMethod.apply(stub, arg1)));
  }

  @Override
  public <T, A1, A2> TemporalOperationResult<Void> startWorkflow(
      Class<T> workflowClass,
      Functions.Proc3<T, A1, A2> workflowMethod,
      A1 arg1,
      A2 arg2,
      WorkflowOptions options) {
    T stub = client.newWorkflowStub(workflowClass, options);
    return invokeAndReturn(
        WorkflowHandle.fromWorkflowMethod(() -> workflowMethod.apply(stub, arg1, arg2)));
  }

  @Override
  public <T, A1, A2, A3> TemporalOperationResult<Void> startWorkflow(
      Class<T> workflowClass,
      Functions.Proc4<T, A1, A2, A3> workflowMethod,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      WorkflowOptions options) {
    T stub = client.newWorkflowStub(workflowClass, options);
    return invokeAndReturn(
        WorkflowHandle.fromWorkflowMethod(() -> workflowMethod.apply(stub, arg1, arg2, arg3)));
  }

  @Override
  public <T, A1, A2, A3, A4> TemporalOperationResult<Void> startWorkflow(
      Class<T> workflowClass,
      Functions.Proc5<T, A1, A2, A3, A4> workflowMethod,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      WorkflowOptions options) {
    T stub = client.newWorkflowStub(workflowClass, options);
    return invokeAndReturn(
        WorkflowHandle.fromWorkflowMethod(
            () -> workflowMethod.apply(stub, arg1, arg2, arg3, arg4)));
  }

  @Override
  public <T, A1, A2, A3, A4, A5> TemporalOperationResult<Void> startWorkflow(
      Class<T> workflowClass,
      Functions.Proc6<T, A1, A2, A3, A4, A5> workflowMethod,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      WorkflowOptions options) {
    T stub = client.newWorkflowStub(workflowClass, options);
    return invokeAndReturn(
        WorkflowHandle.fromWorkflowMethod(
            () -> workflowMethod.apply(stub, arg1, arg2, arg3, arg4, arg5)));
  }

  @Override
  public <T, A1, A2, A3, A4, A5, A6> TemporalOperationResult<Void> startWorkflow(
      Class<T> workflowClass,
      Functions.Proc7<T, A1, A2, A3, A4, A5, A6> workflowMethod,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6,
      WorkflowOptions options) {
    T stub = client.newWorkflowStub(workflowClass, options);
    return invokeAndReturn(
        WorkflowHandle.fromWorkflowMethod(
            () -> workflowMethod.apply(stub, arg1, arg2, arg3, arg4, arg5, arg6)));
  }

  // ---------- Untyped ----------

  @Override
  public <R> TemporalOperationResult<R> startWorkflow(
      String workflowType, Class<R> resultClass, WorkflowOptions options, Object... args) {
    return startWorkflow(workflowType, resultClass, null, options, args);
  }

  @Override
  public <R> TemporalOperationResult<R> startWorkflow(
      String workflowType,
      Class<R> resultClass,
      Type resultType,
      WorkflowOptions options,
      Object... args) {
    WorkflowStub stub = client.newUntypedWorkflowStub(workflowType, options);
    WorkflowHandle<R> handle = WorkflowHandle.fromWorkflowStub(stub, resultClass, args);
    return invokeAndReturn(handle);
  }

  private <R> TemporalOperationResult<R> invokeAndReturn(WorkflowHandle<R> handle) {
    markAsyncOperationStarted();
    try {
      NexusStartWorkflowResponse response =
          NexusStartWorkflowHelper.startWorkflowAndAttachLinks(
              operationContext,
              operationStartDetails,
              request -> handle.getInvoker().invoke(request));
      return TemporalOperationResult.async(response.getOperationToken());
    } catch (Throwable t) {
      // Reset on failure so that if startWorkflowAndAttachLinks throws,
      // the handler can retry without being blocked by the guard.
      asyncOperationStarted.set(false);
      throw t;
    }
  }

  // ---------- Activity overloads (Func returning) ----------

  @Override
  public <I, R> TemporalOperationResult<R> startActivity(
      Class<I> activityInterface,
      Functions.Func1<I, R> activityMethod,
      StartActivityOptions options) {
    Method method = MethodExtractor.extract(activityInterface, activityMethod);
    String activityType = MethodExtractor.activityTypeName(activityInterface, method);
    return startActivityImpl(activityType, Collections.emptyList(), options);
  }

  @Override
  public <I, A1, R> TemporalOperationResult<R> startActivity(
      Class<I> activityInterface,
      Functions.Func2<I, A1, R> activityMethod,
      A1 arg1,
      StartActivityOptions options) {
    Method method = MethodExtractor.extract(activityInterface, activityMethod);
    String activityType = MethodExtractor.activityTypeName(activityInterface, method);
    return startActivityImpl(activityType, Collections.singletonList(arg1), options);
  }

  @Override
  public <I, A1, A2, R> TemporalOperationResult<R> startActivity(
      Class<I> activityInterface,
      Functions.Func3<I, A1, A2, R> activityMethod,
      A1 arg1,
      A2 arg2,
      StartActivityOptions options) {
    Method method = MethodExtractor.extract(activityInterface, activityMethod);
    String activityType = MethodExtractor.activityTypeName(activityInterface, method);
    return startActivityImpl(activityType, Arrays.asList(arg1, arg2), options);
  }

  @Override
  public <I, A1, A2, A3, R> TemporalOperationResult<R> startActivity(
      Class<I> activityInterface,
      Functions.Func4<I, A1, A2, A3, R> activityMethod,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      StartActivityOptions options) {
    Method method = MethodExtractor.extract(activityInterface, activityMethod);
    String activityType = MethodExtractor.activityTypeName(activityInterface, method);
    return startActivityImpl(activityType, Arrays.asList(arg1, arg2, arg3), options);
  }

  @Override
  public <I, A1, A2, A3, A4, R> TemporalOperationResult<R> startActivity(
      Class<I> activityInterface,
      Functions.Func5<I, A1, A2, A3, A4, R> activityMethod,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      StartActivityOptions options) {
    Method method = MethodExtractor.extract(activityInterface, activityMethod);
    String activityType = MethodExtractor.activityTypeName(activityInterface, method);
    return startActivityImpl(activityType, Arrays.asList(arg1, arg2, arg3, arg4), options);
  }

  @Override
  public <I, A1, A2, A3, A4, A5, R> TemporalOperationResult<R> startActivity(
      Class<I> activityInterface,
      Functions.Func6<I, A1, A2, A3, A4, A5, R> activityMethod,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      StartActivityOptions options) {
    Method method = MethodExtractor.extract(activityInterface, activityMethod);
    String activityType = MethodExtractor.activityTypeName(activityInterface, method);
    return startActivityImpl(activityType, Arrays.asList(arg1, arg2, arg3, arg4, arg5), options);
  }

  @Override
  public <I, A1, A2, A3, A4, A5, A6, R> TemporalOperationResult<R> startActivity(
      Class<I> activityInterface,
      Functions.Func7<I, A1, A2, A3, A4, A5, A6, R> activityMethod,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6,
      StartActivityOptions options) {
    Method method = MethodExtractor.extract(activityInterface, activityMethod);
    String activityType = MethodExtractor.activityTypeName(activityInterface, method);
    return startActivityImpl(
        activityType, Arrays.asList(arg1, arg2, arg3, arg4, arg5, arg6), options);
  }

  // ---------- Activity overloads (Proc void) ----------

  @Override
  public <I> TemporalOperationResult<Void> startActivity(
      Class<I> activityInterface, Functions.Proc1<I> activityMethod, StartActivityOptions options) {
    Method method = MethodExtractor.extract(activityInterface, activityMethod);
    String activityType = MethodExtractor.activityTypeName(activityInterface, method);
    return startActivityImpl(activityType, Collections.emptyList(), options);
  }

  @Override
  public <I, A1> TemporalOperationResult<Void> startActivity(
      Class<I> activityInterface,
      Functions.Proc2<I, A1> activityMethod,
      A1 arg1,
      StartActivityOptions options) {
    Method method = MethodExtractor.extract(activityInterface, activityMethod);
    String activityType = MethodExtractor.activityTypeName(activityInterface, method);
    return startActivityImpl(activityType, Collections.singletonList(arg1), options);
  }

  @Override
  public <I, A1, A2> TemporalOperationResult<Void> startActivity(
      Class<I> activityInterface,
      Functions.Proc3<I, A1, A2> activityMethod,
      A1 arg1,
      A2 arg2,
      StartActivityOptions options) {
    Method method = MethodExtractor.extract(activityInterface, activityMethod);
    String activityType = MethodExtractor.activityTypeName(activityInterface, method);
    return startActivityImpl(activityType, Arrays.asList(arg1, arg2), options);
  }

  @Override
  public <I, A1, A2, A3> TemporalOperationResult<Void> startActivity(
      Class<I> activityInterface,
      Functions.Proc4<I, A1, A2, A3> activityMethod,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      StartActivityOptions options) {
    Method method = MethodExtractor.extract(activityInterface, activityMethod);
    String activityType = MethodExtractor.activityTypeName(activityInterface, method);
    return startActivityImpl(activityType, Arrays.asList(arg1, arg2, arg3), options);
  }

  @Override
  public <I, A1, A2, A3, A4> TemporalOperationResult<Void> startActivity(
      Class<I> activityInterface,
      Functions.Proc5<I, A1, A2, A3, A4> activityMethod,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      StartActivityOptions options) {
    Method method = MethodExtractor.extract(activityInterface, activityMethod);
    String activityType = MethodExtractor.activityTypeName(activityInterface, method);
    return startActivityImpl(activityType, Arrays.asList(arg1, arg2, arg3, arg4), options);
  }

  @Override
  public <I, A1, A2, A3, A4, A5> TemporalOperationResult<Void> startActivity(
      Class<I> activityInterface,
      Functions.Proc6<I, A1, A2, A3, A4, A5> activityMethod,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      StartActivityOptions options) {
    Method method = MethodExtractor.extract(activityInterface, activityMethod);
    String activityType = MethodExtractor.activityTypeName(activityInterface, method);
    return startActivityImpl(activityType, Arrays.asList(arg1, arg2, arg3, arg4, arg5), options);
  }

  @Override
  public <I, A1, A2, A3, A4, A5, A6> TemporalOperationResult<Void> startActivity(
      Class<I> activityInterface,
      Functions.Proc7<I, A1, A2, A3, A4, A5, A6> activityMethod,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6,
      StartActivityOptions options) {
    Method method = MethodExtractor.extract(activityInterface, activityMethod);
    String activityType = MethodExtractor.activityTypeName(activityInterface, method);
    return startActivityImpl(
        activityType, Arrays.asList(arg1, arg2, arg3, arg4, arg5, arg6), options);
  }

  // ---------- Activity untyped ----------

  @Override
  public <R> TemporalOperationResult<R> startActivity(
      String activityType, Class<R> resultClass, StartActivityOptions options, Object... args) {
    List<Object> argList = args == null ? Collections.emptyList() : Arrays.asList(args);
    return startActivityImpl(activityType, argList, options);
  }

  private <R> TemporalOperationResult<R> startActivityImpl(
      String activityType, List<Object> args, StartActivityOptions options) {
    markAsyncOperationStarted();
    InternalNexusOperationContext nexusContext = CurrentNexusOperationContext.get();
    try {
      NexusOperationMetadata nexusOperationMetadata =
          new NexusOperationMetadata(
              operationStartDetails.getRequestId(),
              operationStartDetails.getCallbackUrl(),
              operationStartDetails.getCallbackHeaders());
      nexusContext.setNexusOperationMetadata(nexusOperationMetadata);
      NexusStartActivityResponse response =
          NexusStartActivityHelper.startActivityAndAttachLinks(
              operationContext,
              operationStartDetails,
              activityType,
              args,
              options,
              Header.empty(),
              request -> {
                ActivityClientCallsInterceptor.StartActivityInput input =
                    new ActivityClientCallsInterceptor.StartActivityInput(
                        request.getActivityType(),
                        request.getArgs(),
                        request.getOptions(),
                        request.getHeader());
                // Build an ActivityClient that mirrors the surrounding WorkflowClient's options
                // so that the metrics-tagged scope built by the impl is preserved. setIdentity is
                // load-bearing because the cancel RPC reads it from clientOptions.getIdentity().
                ActivityClient activityClient =
                    ActivityClient.newInstance(
                        client.getWorkflowServiceStubs(),
                        ActivityClientOptions.newBuilder()
                            .setNamespace(client.getOptions().getNamespace())
                            .setDataConverter(client.getOptions().getDataConverter())
                            .setIdentity(client.getOptions().getIdentity())
                            .build());
                ActivityClientCallsInterceptor.StartActivityOutput out =
                    ((ActivityClientInternal) activityClient).getInvoker().startActivity(input);
                // The invoker generated and injected the runId-free token into the callback
                // headers before the start RPC fired. The header token cannot include a run ID
                // because the run ID isn't known until after the start RPC returns. The operation
                // token returned to the Nexus caller can — and should — include it, so it's
                // regenerated here from the same activity ID + the run ID the start RPC produced.
                String headerToken = nexusOperationMetadata.operationToken;
                if (headerToken == null) {
                  throw new HandlerException(
                      HandlerException.ErrorType.INTERNAL,
                      "invoker did not return a Nexus operation token for activity start with callback",
                      new IllegalStateException(
                          "operationToken is null on NexusOperationMetadata after activity start"));
                }
                String returnToken;
                try {
                  returnToken =
                      OperationTokenUtil.generateActivityExecutionOperationToken(
                          out.getActivityId(),
                          out.getActivityRunId(),
                          client.getOptions().getNamespace());
                } catch (JsonProcessingException e) {
                  throw new HandlerException(
                      HandlerException.ErrorType.INTERNAL,
                      "failed to generate activity operation token",
                      e);
                }
                return new NexusStartActivityResponse(
                    out.getActivityId(), out.getActivityRunId(), returnToken);
              });
      return TemporalOperationResult.async(response.getOperationToken());
    } catch (Throwable t) {
      // Reset on failure so that if the activity start throws, the handler can retry without
      // being blocked by the guard.
      asyncOperationStarted.set(false);
      throw t;
    } finally {
      nexusContext.setNexusOperationMetadata(null);
    }
  }

  private void markAsyncOperationStarted() {
    if (!asyncOperationStarted.compareAndSet(false, true)) {
      throw new HandlerException(
          HandlerException.ErrorType.BAD_REQUEST,
          new IllegalStateException(
              "Only one async operation can be started per operation handler invocation. "
                  + "Use getWorkflowClient() for additional workflow interactions."));
    }
  }
}
