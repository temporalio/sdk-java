package io.temporal.nexus;

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

/**
 * Nexus-aware client wrapping {@link WorkflowClient}. Provides methods for interacting with
 * Temporal workflows from within a Nexus operation handler.
 *
 * <p>Obtained via the {@link TemporalOperationHandler.StartFunction} parameter. The client creates
 * workflow stubs internally — users pass the workflow class, a lambda that calls the workflow
 * method, and workflow options.
 *
 * <p>Usage example:
 *
 * <pre>{@code
 * @OperationImpl
 * public OperationHandler<OrderInput, OrderResult> createOrder() {
 *   return TemporalOperationHandler.from((context, client, input) -> {
 *     return client.startWorkflow(
 *         OrderWorkflow.class,
 *         wf -> wf.processOrder(input),
 *         WorkflowOptions.newBuilder()
 *             .setWorkflowId("order-" + context.getRequestId())
 *             .build());
 *   });
 * }
 * }</pre>
 *
 * <p>For advanced use cases, the underlying {@link WorkflowClient} can be accessed via {@link
 * #getWorkflowClient()}. For example, to send a signal and return a synchronous result:
 *
 * <pre>{@code
 * @OperationImpl
 * public OperationHandler<CancelOrderInput, Void> cancelOrder() {
 *   return TemporalOperationHandler.from((context, client, input) -> {
 *     client.getWorkflowClient()
 *         .newUntypedWorkflowStub("order-" + input.getOrderId())
 *         .signal("requestCancellation", input);
 *     return TemporalOperationResult.sync(null);
 *   });
 * }
 * }</pre>
 */
@Experimental
public final class TemporalNexusClient {

  private final WorkflowClient client;
  private final OperationContext operationContext;
  private final OperationStartDetails operationStartDetails;

  TemporalNexusClient(
      WorkflowClient client,
      OperationContext operationContext,
      OperationStartDetails operationStartDetails) {
    this.client = Objects.requireNonNull(client);
    this.operationContext = Objects.requireNonNull(operationContext);
    this.operationStartDetails = Objects.requireNonNull(operationStartDetails);
  }

  /** Returns the underlying {@link WorkflowClient} for advanced use cases. */
  public WorkflowClient getWorkflowClient() {
    return client;
  }

  /**
   * Starts a workflow by invoking a returning method on a workflow stub. The client creates the
   * stub from the given class and options, then invokes the workflow method via the provided
   * function.
   *
   * <p>Example:
   *
   * <pre>{@code
   * client.startWorkflow(MyWorkflow.class, wf -> wf.run(input), options)
   * }</pre>
   *
   * <p>For void-returning workflow methods, use a block lambda that returns null:
   *
   * <pre>{@code
   * client.startWorkflow(MyWorkflow.class, wf -> { wf.execute(input); return null; }, options)
   * }</pre>
   *
   * @param workflowClass the workflow interface class
   * @param workflowMethod receives the workflow stub and calls exactly one workflow method
   * @param options workflow start options (must include workflowId)
   * @param <T> the workflow interface type
   * @param <R> the workflow return type
   * @return an async {@link TemporalOperationResult} with the workflow-run operation token
   */
  public <T, R> TemporalOperationResult<R> startWorkflow(
      Class<T> workflowClass, Functions.Func1<T, R> workflowMethod, WorkflowOptions options) {
    T stub = client.newWorkflowStub(workflowClass, options);
    Functions.Func<R> bound = () -> workflowMethod.apply(stub);
    return invokeAndReturn(WorkflowHandle.fromWorkflowMethod(bound));
  }

  /**
   * Starts a workflow using an untyped workflow type name.
   *
   * @param workflowType the workflow type name string
   * @param resultClass the expected result class
   * @param args workflow arguments
   * @param options workflow start options (must include workflowId)
   * @param <R> the workflow return type
   * @return an async {@link TemporalOperationResult} with the workflow-run operation token
   */
  public <R> TemporalOperationResult<R> startWorkflow(
      String workflowType, Class<R> resultClass, Object[] args, WorkflowOptions options) {
    WorkflowStub stub = client.newUntypedWorkflowStub(workflowType, options);
    WorkflowHandle<R> handle = WorkflowHandle.fromWorkflowStub(stub, resultClass, args);
    return invokeAndReturn(handle);
  }

  private <R> TemporalOperationResult<R> invokeAndReturn(WorkflowHandle<R> handle) {
    NexusStartWorkflowResponse response =
        NexusStartWorkflowHelper.startWorkflowAndAttachLinks(
            operationContext,
            operationStartDetails,
            request -> handle.getInvoker().invoke(request));
    return TemporalOperationResult.async(response.getOperationToken());
  }
}
