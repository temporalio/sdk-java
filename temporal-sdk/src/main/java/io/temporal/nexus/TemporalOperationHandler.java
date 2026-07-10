package io.temporal.nexus;

import io.nexusrpc.OperationException;
import io.nexusrpc.handler.*;
import io.temporal.client.WorkflowClient;
import io.temporal.common.Experimental;
import io.temporal.internal.nexus.CurrentNexusOperationContext;
import io.temporal.internal.nexus.InternalNexusOperationContext;
import io.temporal.internal.nexus.OperationToken;
import io.temporal.internal.nexus.OperationTokenUtil;

/**
 * Generic Nexus operation handler backed by Temporal. Implements {@link OperationHandler} and
 * provides a composable way to map Temporal operations (start workflow, etc.) to Nexus operations.
 *
 * <p>Usage example:
 *
 * <pre>{@code
 * @OperationImpl
 * public OperationHandler<TransferInput, TransferResult> startTransfer() {
 *   return TemporalOperationHandler.create((context, client, input) -> {
 *     return client.startWorkflow(
 *         TransferWorkflow.class,
 *         TransferWorkflow::transfer, input.getFromAccount(), input.getToAccount(),
 *         WorkflowOptions.newBuilder()
 *             .setWorkflowId("transfer-" + input.getTransferId())
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
   * Handler invoked when a Nexus start operation request is received.
   *
   * @param <T> the input type
   * @param <R> the result type
   */
  @FunctionalInterface
  public interface StartHandler<T, R> {
    TemporalOperationResult<R> apply(
        TemporalOperationStartContext context, TemporalNexusClient client, T input)
        throws OperationException;
  }

  private final StartHandler<T, R> startHandler;

  protected TemporalOperationHandler(StartHandler<T, R> startHandler) {
    this.startHandler = startHandler;
  }

  /**
   * Creates a {@link TemporalOperationHandler} from a start handler. Subclass and override {@link
   * #cancelWorkflowRun} to customize cancel behavior.
   *
   * @param startHandler the handler to invoke on start operation requests
   * @return an operation handler backed by the given start handler
   */
  public static <T, R> TemporalOperationHandler<T, R> create(StartHandler<T, R> startHandler) {
    return new TemporalOperationHandler<>(startHandler);
  }

  @Override
  public final OperationStartResult<R> start(
      OperationContext ctx, OperationStartDetails details, T input) throws OperationException {
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
    switch (token.getType()) {
      case WORKFLOW_RUN:
        cancelWorkflowRun(cancelContext, new CancelWorkflowRunInput(token.getWorkflowId()));
        break;
      case WORKFLOW_UPDATE:
        cancelUpdateWorkflow(
            cancelContext,
            new CancelUpdateWorkflowExecutionInput(
                token.getWorkflowId(), token.getRunId(), token.getUpdateId()));
        break;
      default:
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

  /**
   * Called when a cancel request is received for a workflow update token. Override to customize
   * cancel behavior.
   *
   * <p>Default behavior: not implemented. There is no server primitive to cancel an in-flight
   * workflow update.
   *
   * @param context the cancel context
   * @param input describes the update to cancel
   */
  protected void cancelUpdateWorkflow(
      TemporalOperationCancelContext context, CancelUpdateWorkflowExecutionInput input) {
    throw new HandlerException(
        HandlerException.ErrorType.NOT_IMPLEMENTED,
        new UnsupportedOperationException("cannot cancel an UpdateWorkflow operation"));
  }
}
