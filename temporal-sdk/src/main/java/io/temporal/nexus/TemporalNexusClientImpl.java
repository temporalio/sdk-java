package io.temporal.nexus;

import io.nexusrpc.handler.HandlerException;
import io.nexusrpc.handler.OperationContext;
import io.nexusrpc.handler.OperationStartDetails;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.Experimental;
import io.temporal.internal.client.NexusStartWorkflowResponse;
import io.temporal.internal.nexus.NexusStartWorkflowHelper;
import io.temporal.workflow.Functions;
import java.util.Objects;

/** Package-private implementation of {@link TemporalNexusClient}. */
@Experimental
final class TemporalNexusClientImpl implements TemporalNexusClient {

  private final WorkflowClient client;
  private final OperationContext operationContext;
  private final OperationStartDetails operationStartDetails;
  private boolean asyncOperationStarted;

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

  // ---------- Untyped ----------

  @Override
  public <R> TemporalOperationResult<R> startWorkflow(
      String workflowType, Class<R> resultClass, WorkflowOptions options, Object... args) {
    WorkflowStub stub = client.newUntypedWorkflowStub(workflowType, options);
    WorkflowHandle<R> handle = WorkflowHandle.fromWorkflowStub(stub, resultClass, args);
    return invokeAndReturn(handle);
  }

  private <R> TemporalOperationResult<R> invokeAndReturn(WorkflowHandle<R> handle) {
    if (asyncOperationStarted) {
      throw new HandlerException(
          HandlerException.ErrorType.BAD_REQUEST,
          new IllegalStateException(
              "Only one async operation can be started per operation handler invocation. "
                  + "Use getWorkflowClient() for additional workflow interactions."));
    }
    NexusStartWorkflowResponse response =
        NexusStartWorkflowHelper.startWorkflowAndAttachLinks(
            operationContext,
            operationStartDetails,
            request -> handle.getInvoker().invoke(request));
    // Set after successful start so that if startWorkflowAndAttachLinks throws,
    // the handler can retry without being blocked by the guard.
    asyncOperationStarted = true;
    return TemporalOperationResult.async(response.getOperationToken());
  }
}
