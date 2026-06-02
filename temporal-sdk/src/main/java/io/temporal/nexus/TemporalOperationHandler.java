package io.temporal.nexus;

import io.nexusrpc.handler.*;
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
 * <p>For the common case (default cancel behavior), prefer {@link TemporalOperation}, which
 * collapses the operation factory into the method itself. Subclass this class only when you need to
 * customize cancel behavior. Override {@link #cancelWorkflowRun} to change how workflow-run
 * cancellations are handled. The {@link #start} and {@link #cancel} methods should not be
 * overridden — they contain the core dispatch logic.
 *
 * <p>Custom-cancel example:
 *
 * <pre>{@code
 * @OperationImpl
 * public OperationHandler<TransferInput, TransferResult> startTransfer() {
 *   return new TemporalOperationHandler<TransferInput, TransferResult>(
 *       (context, client, input) ->
 *           client.startWorkflow(
 *               TransferWorkflow.class,
 *               TransferWorkflow::transfer,
 *               input,
 *               WorkflowOptions.newBuilder()
 *                   .setWorkflowId("transfer-" + input.getTransferId())
 *                   .build())) {
 *     @Override
 *     protected void cancelWorkflowRun(
 *         TemporalOperationCancelContext ctx, CancelWorkflowRunInput input) {
 *       // custom logic
 *     }
 *   };
 * }
 * }</pre>
 *
 * @param <T> the input type
 * @param <R> the result type
 */
@Experimental
public class TemporalOperationHandler<T, R> implements OperationHandler<T, R> {

  /**
   * Handler invoked when a Nexus start operation request is received.
   *
   * @param <T> the input type
   * @param <R> the result type
   */
  @FunctionalInterface
  public interface StartHandler<T, R> {
    TemporalOperationResult<R> apply(
        TemporalOperationStartContext context, TemporalNexusClient client, T input);
  }

  private final StartHandler<T, R> startHandler;

  protected TemporalOperationHandler(StartHandler<T, R> startHandler) {
    this.startHandler = startHandler;
  }

  @Override
  public final OperationStartResult<R> start(
      OperationContext ctx, OperationStartDetails details, T input) {
    InternalNexusOperationContext nexusCtx = CurrentNexusOperationContext.get();
    TemporalNexusClient client =
        new TemporalNexusClientImpl(nexusCtx.getWorkflowClient(), ctx, details);

    TemporalOperationStartContext startContext = new TemporalOperationStartContext(ctx, details);
    TemporalOperationResult<R> result = startHandler.apply(startContext, client, input);

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
  public final void cancel(OperationContext ctx, OperationCancelDetails details) {
    OperationToken token;
    try {
      token = OperationTokenUtil.loadOperationToken(details.getOperationToken());
    } catch (IllegalArgumentException e) {
      throw new HandlerException(
          HandlerException.ErrorType.BAD_REQUEST, "failed to parse operation token", e);
    }

    TemporalOperationCancelContext cancelContext = new TemporalOperationCancelContext(ctx, details);
    if (token.getType() == OperationTokenType.WORKFLOW_RUN) {
      cancelWorkflowRun(cancelContext, new CancelWorkflowRunInput(token.getWorkflowId()));
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
   * @param input describes the workflow run to cancel
   */
  protected void cancelWorkflowRun(
      TemporalOperationCancelContext context, CancelWorkflowRunInput input) {
    WorkflowClient client = CurrentNexusOperationContext.get().getWorkflowClient();
    client.newUntypedWorkflowStub(input.getWorkflowId()).cancel();
  }
}
