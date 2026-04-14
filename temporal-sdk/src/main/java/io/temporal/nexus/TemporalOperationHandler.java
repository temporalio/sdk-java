package io.temporal.nexus;

import io.nexusrpc.handler.*;
import io.nexusrpc.handler.OperationHandler;
import io.temporal.client.WorkflowClient;
import io.temporal.common.Experimental;
import io.temporal.internal.nexus.CurrentNexusOperationContext;
import io.temporal.internal.nexus.InternalNexusOperationContext;
import io.temporal.internal.nexus.OperationToken;
import io.temporal.internal.nexus.OperationTokenType;
import io.temporal.internal.nexus.OperationTokenUtil;

/**
 * Generic Nexus operation handler backed by Temporal. Implements {@link OperationHandler} and
 * provides a composable way to map Temporal operations (start workflow, etc.) to Nexus operations.
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
 * <p>This class supports subclassing to customize cancel behavior. Override {@link
 * #cancelWorkflowRun} to change how workflow-run cancellations are handled. The {@link #start} and
 * {@link #cancel} methods should not be overridden — they contain the core dispatch logic.
 *
 * @param <T> the input type
 * @param <R> the result type
 */
@Experimental
public class TemporalOperationHandler<T, R> implements OperationHandler<T, R> {

  /**
   * Function invoked when a Nexus start operation request is received.
   *
   * @param <T> the input type
   * @param <R> the result type
   */
  @FunctionalInterface
  public interface StartFunction<T, R> {
    TemporalOperationResult<R> apply(
        TemporalOperationStartContext context, TemporalNexusClient client, T input);
  }

  private final StartFunction<T, R> startFunction;

  protected TemporalOperationHandler(StartFunction<T, R> startFunction) {
    this.startFunction = startFunction;
  }

  /**
   * Creates a {@link TemporalOperationHandler} from a start function. Subclass and override {@link
   * #cancelWorkflowRun} to customize cancel behavior.
   *
   * @param startFunction the function to invoke on start operation requests
   * @return an operation handler backed by the given start function
   */
  public static <T, R> TemporalOperationHandler<T, R> from(StartFunction<T, R> startFunction) {
    return new TemporalOperationHandler<>(startFunction);
  }

  @Override
  public OperationStartResult<R> start(
      OperationContext ctx, OperationStartDetails details, T input) {
    InternalNexusOperationContext nexusCtx = CurrentNexusOperationContext.get();
    TemporalNexusClient client =
        new TemporalNexusClient(nexusCtx.getWorkflowClient(), ctx, details);

    TemporalOperationStartContext startContext = new TemporalOperationStartContext(ctx, details);
    TemporalOperationResult<R> result = startFunction.apply(startContext, client, input);

    if (result.isSync()) {
      return OperationStartResult.newSyncBuilder(result.getSyncResult()).build();
    } else if (result.isAsync()) {
      return OperationStartResult.<R>newAsyncBuilder(result.getAsyncOperationToken()).build();
    } else {
      throw new HandlerException(
          HandlerException.ErrorType.INTERNAL,
          new IllegalStateException("TemporalOperationResult must be either sync or async"));
    }
  }

  @Override
  public void cancel(OperationContext ctx, OperationCancelDetails details) {
    OperationToken token;
    try {
      token = OperationTokenUtil.loadOperationToken(details.getOperationToken());
    } catch (IllegalArgumentException e) {
      throw new HandlerException(
          HandlerException.ErrorType.BAD_REQUEST, "failed to parse operation token", e);
    }

    TemporalOperationCancelContext cancelContext = new TemporalOperationCancelContext(ctx, details);
    if (token.getType() == OperationTokenType.WORKFLOW_RUN) {
      cancelWorkflowRun(cancelContext, token.getWorkflowId());
    } else {
      throw new HandlerException(
          HandlerException.ErrorType.BAD_REQUEST,
          new IllegalArgumentException("unsupported operation token type: " + token.getType()));
    }
  }

  /**
   * Called when a cancel request is received for a workflow-run token (type=1). Override to
   * customize cancel behavior.
   *
   * <p>Default behavior: cancels the underlying workflow.
   *
   * @param context the cancel context
   * @param workflowId the workflow ID extracted from the operation token
   */
  protected void cancelWorkflowRun(TemporalOperationCancelContext context, String workflowId) {
    WorkflowClient client = CurrentNexusOperationContext.get().getWorkflowClient();
    client.newUntypedWorkflowStub(workflowId).cancel();
  }
}
