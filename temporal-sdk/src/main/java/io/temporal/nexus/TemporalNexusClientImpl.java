package io.temporal.nexus;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.base.Strings;
import io.nexusrpc.OperationException;
import io.nexusrpc.handler.HandlerException;
import io.nexusrpc.handler.HandlerException.RetryBehavior;
import io.nexusrpc.handler.OperationContext;
import io.nexusrpc.handler.OperationStartDetails;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.UpdateOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.client.WorkflowTargetOptions;
import io.temporal.client.WorkflowUpdateException;
import io.temporal.client.WorkflowUpdateHandle;
import io.temporal.client.WorkflowUpdateStage;
import io.temporal.common.Experimental;
import io.temporal.internal.client.NexusStartWorkflowResponse;
import io.temporal.internal.nexus.CurrentNexusOperationContext;
import io.temporal.internal.nexus.InternalNexusOperationContext;
import io.temporal.internal.nexus.NexusOperationMetadata;
import io.temporal.internal.nexus.NexusStartWorkflowHelper;
import io.temporal.internal.nexus.OperationToken;
import io.temporal.internal.nexus.OperationTokenUtil;
import io.temporal.workflow.Functions;
import java.lang.reflect.Type;
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

  // ---------- Update Workflow overloads ----------

  @Override
  public <T, R> TemporalOperationResult<R> startWorkflowUpdate(
      Class<T> workflowClass,
      String workflowId,
      Functions.Func1<T, R> updateMethod,
      UpdateOptions<R> options)
      throws OperationException {
    T stub = client.newWorkflowStub(workflowClass, workflowId);
    return executeUpdate(
        options,
        effective -> WorkflowClient.startUpdate(() -> updateMethod.apply(stub), effective));
  }

  @Override
  public <T, A1, R> TemporalOperationResult<R> startWorkflowUpdate(
      Class<T> workflowClass,
      String workflowId,
      Functions.Func2<T, A1, R> updateMethod,
      A1 arg1,
      UpdateOptions<R> options)
      throws OperationException {
    T stub = client.newWorkflowStub(workflowClass, workflowId);
    return executeUpdate(
        options,
        effective -> WorkflowClient.startUpdate(() -> updateMethod.apply(stub, arg1), effective));
  }

  @Override
  public <T, A1, A2, R> TemporalOperationResult<R> startWorkflowUpdate(
      Class<T> workflowClass,
      String workflowId,
      Functions.Func3<T, A1, A2, R> updateMethod,
      A1 arg1,
      A2 arg2,
      UpdateOptions<R> options)
      throws OperationException {
    T stub = client.newWorkflowStub(workflowClass, workflowId);
    return executeUpdate(
        options,
        effective ->
            WorkflowClient.startUpdate(() -> updateMethod.apply(stub, arg1, arg2), effective));
  }

  @Override
  public <T, A1, A2, A3, R> TemporalOperationResult<R> startWorkflowUpdate(
      Class<T> workflowClass,
      String workflowId,
      Functions.Func4<T, A1, A2, A3, R> updateMethod,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      UpdateOptions<R> options)
      throws OperationException {
    T stub = client.newWorkflowStub(workflowClass, workflowId);
    return executeUpdate(
        options,
        effective ->
            WorkflowClient.startUpdate(
                () -> updateMethod.apply(stub, arg1, arg2, arg3), effective));
  }

  @Override
  public <T, A1, A2, A3, A4, R> TemporalOperationResult<R> startWorkflowUpdate(
      Class<T> workflowClass,
      String workflowId,
      Functions.Func5<T, A1, A2, A3, A4, R> updateMethod,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      UpdateOptions<R> options)
      throws OperationException {
    T stub = client.newWorkflowStub(workflowClass, workflowId);
    return executeUpdate(
        options,
        effective ->
            WorkflowClient.startUpdate(
                () -> updateMethod.apply(stub, arg1, arg2, arg3, arg4), effective));
  }

  @Override
  public <T, A1, A2, A3, A4, A5, R> TemporalOperationResult<R> startWorkflowUpdate(
      Class<T> workflowClass,
      String workflowId,
      Functions.Func6<T, A1, A2, A3, A4, A5, R> updateMethod,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      UpdateOptions<R> options)
      throws OperationException {
    T stub = client.newWorkflowStub(workflowClass, workflowId);
    return executeUpdate(
        options,
        effective ->
            WorkflowClient.startUpdate(
                () -> updateMethod.apply(stub, arg1, arg2, arg3, arg4, arg5), effective));
  }

  @Override
  public <T, A1, A2, A3, A4, A5, A6, R> TemporalOperationResult<R> startWorkflowUpdate(
      Class<T> workflowClass,
      String workflowId,
      Functions.Func7<T, A1, A2, A3, A4, A5, A6, R> updateMethod,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6,
      UpdateOptions<R> options)
      throws OperationException {
    T stub = client.newWorkflowStub(workflowClass, workflowId);
    return executeUpdate(
        options,
        effective ->
            WorkflowClient.startUpdate(
                () -> updateMethod.apply(stub, arg1, arg2, arg3, arg4, arg5, arg6), effective));
  }

  @Override
  public <T> TemporalOperationResult<Void> startWorkflowUpdate(
      Class<T> workflowClass,
      String workflowId,
      Functions.Proc1<T> updateMethod,
      UpdateOptions<Void> options)
      throws OperationException {
    T stub = client.newWorkflowStub(workflowClass, workflowId);
    return executeUpdate(
        options,
        effective -> WorkflowClient.startUpdate(() -> updateMethod.apply(stub), effective));
  }

  @Override
  public <T, A1> TemporalOperationResult<Void> startWorkflowUpdate(
      Class<T> workflowClass,
      String workflowId,
      Functions.Proc2<T, A1> updateMethod,
      A1 arg1,
      UpdateOptions<Void> options)
      throws OperationException {
    T stub = client.newWorkflowStub(workflowClass, workflowId);
    return executeUpdate(
        options,
        effective -> WorkflowClient.startUpdate(() -> updateMethod.apply(stub, arg1), effective));
  }

  @Override
  public <T, A1, A2> TemporalOperationResult<Void> startWorkflowUpdate(
      Class<T> workflowClass,
      String workflowId,
      Functions.Proc3<T, A1, A2> updateMethod,
      A1 arg1,
      A2 arg2,
      UpdateOptions<Void> options)
      throws OperationException {
    T stub = client.newWorkflowStub(workflowClass, workflowId);
    return executeUpdate(
        options,
        effective ->
            WorkflowClient.startUpdate(() -> updateMethod.apply(stub, arg1, arg2), effective));
  }

  @Override
  public <T, A1, A2, A3> TemporalOperationResult<Void> startWorkflowUpdate(
      Class<T> workflowClass,
      String workflowId,
      Functions.Proc4<T, A1, A2, A3> updateMethod,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      UpdateOptions<Void> options)
      throws OperationException {
    T stub = client.newWorkflowStub(workflowClass, workflowId);
    return executeUpdate(
        options,
        effective ->
            WorkflowClient.startUpdate(
                () -> updateMethod.apply(stub, arg1, arg2, arg3), effective));
  }

  @Override
  public <T, A1, A2, A3, A4> TemporalOperationResult<Void> startWorkflowUpdate(
      Class<T> workflowClass,
      String workflowId,
      Functions.Proc5<T, A1, A2, A3, A4> updateMethod,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      UpdateOptions<Void> options)
      throws OperationException {
    T stub = client.newWorkflowStub(workflowClass, workflowId);
    return executeUpdate(
        options,
        effective ->
            WorkflowClient.startUpdate(
                () -> updateMethod.apply(stub, arg1, arg2, arg3, arg4), effective));
  }

  @Override
  public <T, A1, A2, A3, A4, A5> TemporalOperationResult<Void> startWorkflowUpdate(
      Class<T> workflowClass,
      String workflowId,
      Functions.Proc6<T, A1, A2, A3, A4, A5> updateMethod,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      UpdateOptions<Void> options)
      throws OperationException {
    T stub = client.newWorkflowStub(workflowClass, workflowId);
    return executeUpdate(
        options,
        effective ->
            WorkflowClient.startUpdate(
                () -> updateMethod.apply(stub, arg1, arg2, arg3, arg4, arg5), effective));
  }

  @Override
  public <T, A1, A2, A3, A4, A5, A6> TemporalOperationResult<Void> startWorkflowUpdate(
      Class<T> workflowClass,
      String workflowId,
      Functions.Proc7<T, A1, A2, A3, A4, A5, A6> updateMethod,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6,
      UpdateOptions<Void> options)
      throws OperationException {
    T stub = client.newWorkflowStub(workflowClass, workflowId);
    return executeUpdate(
        options,
        effective ->
            WorkflowClient.startUpdate(
                () -> updateMethod.apply(stub, arg1, arg2, arg3, arg4, arg5, arg6), effective));
  }

  /** Function that will trigger {@code startUpdate} on overloads */
  @FunctionalInterface
  private interface UpdateCommand<R> {
    WorkflowUpdateHandle<R> triggerUpdate(UpdateOptions<R> options);
  }

  /** Common code for all {@code startWorkflowUpdate} overloads. */
  private <R> TemporalOperationResult<R> executeUpdate(
      UpdateOptions<R> options, UpdateCommand<R> updateWrapper) throws OperationException {

    UpdateOptions.Builder<R> effectiveOptsBuilder = UpdateOptions.newBuilder(options);
    String requestId = operationStartDetails.getRequestId();
    if (Strings.isNullOrEmpty(options.getUpdateId())) {
      // if updateId is unset, use requestId - consistent with other SDKs
      effectiveOptsBuilder.setUpdateId(requestId);
    }
    options = effectiveOptsBuilder.build();
    checkNexusUpdateOptionsValid(options);
    markAsyncOperationStarted();

    InternalNexusOperationContext nexusContext = CurrentNexusOperationContext.get();
    try {
      String callbackUrl = operationStartDetails.getCallbackUrl();
      if (Strings.isNullOrEmpty(callbackUrl)) {
        throw new HandlerException(
            HandlerException.ErrorType.BAD_REQUEST,
            new IllegalArgumentException("callback URL is required for a Nexus operation"));
      }
      NexusOperationMetadata nexusOperationMetadata =
          new NexusOperationMetadata(
              requestId, callbackUrl, operationStartDetails.getCallbackHeaders());
      // set the nexusOperationMetadata and capture operationCompleted
      nexusContext.setNexusOperationMetadata(nexusOperationMetadata);
      WorkflowUpdateHandle<R> handle = updateWrapper.triggerUpdate(options);
      if (nexusOperationMetadata.operationCompleted) {
        try {
          R value = handle.getResult();
          return TemporalOperationResult.sync(value);
        } catch (WorkflowUpdateException e) {
          // Only case where operation is completed but getResult fails is if the update
          // fails non-retriably - validation failure - so fail the operation immediately
          throw OperationException.failed(e);
        }
      }
      // regenerate token so it has the actual run ID that update is running on
      // previous generation is only to handle completion before handle is returned
      String token = "";
      try {
        OperationToken ot =
            OperationTokenUtil.loadWorkflowUpdateOperationToken(
                nexusOperationMetadata.operationToken);
        token =
            OperationTokenUtil.generateWorkflowUpdateOperationToken(
                ot.getNamespace(),
                ot.getWorkflowId(),
                handle.getExecution().getRunId(),
                ot.getUpdateId());
      } catch (IllegalArgumentException | JsonProcessingException e) {
        // should not happen, this is all in SDK
        throw new HandlerException(
            HandlerException.ErrorType.INTERNAL, "unexpected error reconstructing token", e);
      }
      return TemporalOperationResult.async(token);
    } catch (Throwable t) {
      // Reset on failure so that if the update RPC throws, the handler can retry without being
      // blocked by the guard.
      asyncOperationStarted.set(false);
      throw t;
    } finally {
      nexusContext.setNexusOperationMetadata(null);
    }
  }

  /**
   * @throws OperationException if the options provided are invalid like missing
   *     UpdateName/WorkflowID/etc
   */
  private <R> void checkNexusUpdateOptionsValid(UpdateOptions<R> options)
      throws OperationException {
    if (options.getWaitForStage() != WorkflowUpdateStage.ACCEPTED) {
      throw new HandlerException(
          HandlerException.ErrorType.BAD_REQUEST,
          "invalid update request",
          new IllegalArgumentException(
              "nexus op workflow updates only support WorkflowUpdateStageAccepted for async updates"),
          RetryBehavior.RETRYABLE);
    }
    try {
      options.validate();
    } catch (IllegalStateException e) {
      throw new HandlerException(
          HandlerException.ErrorType.INTERNAL,
          "invalid update request",
          e,
          RetryBehavior.RETRYABLE);
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

  @Override
  public <T, A1, A2, A3, A4, A5, A6, R> TemporalOperationResult<R> startWorkflowUpdate(
      Class<T> workflowClass,
      WorkflowExecution execution,
      Functions.Func7<T, A1, A2, A3, A4, A5, A6, R> updateMethod,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6,
      UpdateOptions<R> options)
      throws OperationException {
    T stub =
        client.newWorkflowStub(
            workflowClass,
            WorkflowTargetOptions.newBuilder().setWorkflowExecution(execution).build());
    return executeUpdate(
        options,
        effective ->
            WorkflowClient.startUpdate(
                () -> updateMethod.apply(stub, arg1, arg2, arg3, arg4, arg5, arg6), effective));
  }
}
