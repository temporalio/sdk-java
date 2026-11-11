package io.temporal.internal.sync;

import static io.temporal.internal.sync.WorkflowInternal.unwrap;
import static io.temporal.serviceclient.CheckedExceptionWrapper.wrap;

import io.temporal.api.common.v1.Payloads;
import io.temporal.api.history.v1.WorkflowExecutionStartedEventAttributes;
import io.temporal.common.converter.DataConverter;
import io.temporal.failure.CanceledFailure;
import io.temporal.failure.TemporalFailure;
import io.temporal.internal.replay.ReplayWorkflowContext;
import io.temporal.internal.statemachines.UnsupportedContinueAsNewRequest;
import io.temporal.internal.worker.WorkflowExecutionException;
import io.temporal.worker.WorkflowImplementationOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInfo;
import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class WorkflowExecutionHandler {

  private static final Logger log = LoggerFactory.getLogger(WorkflowExecutionHandler.class);

  private final SyncWorkflowContext context;
  private final SyncWorkflowDefinition workflow;
  private final WorkflowExecutionStartedEventAttributes attributes;
  @Nonnull private final WorkflowImplementationOptions implementationOptions;

  private Optional<Payloads> output = Optional.empty();
  private boolean done;

  public WorkflowExecutionHandler(
      SyncWorkflowContext context,
      SyncWorkflowDefinition workflow,
      WorkflowExecutionStartedEventAttributes attributes,
      @Nonnull WorkflowImplementationOptions options) {
    this.implementationOptions = options;
    this.context = Objects.requireNonNull(context);
    this.workflow = Objects.requireNonNull(workflow);
    this.attributes = Objects.requireNonNull(attributes);
  }

  public void runWorkflowMethod() {
    try {
      Optional<Payloads> input =
          attributes.hasInput() ? Optional.of(attributes.getInput()) : Optional.empty();
      output = workflow.execute(context.headerWithCodec(attributes.getHeader()), input);
    } catch (Throwable e) {
      applyWorkflowFailurePolicyAndRethrow(e);
    } finally {
      done = true;
    }
  }

  /** Runs the workflow class constructor. */
  public void runConstructor() {
    try {
      workflow.initialize(
          attributes.hasInput() ? Optional.of(attributes.getInput()) : Optional.empty());
    } catch (Throwable e) {
      applyWorkflowFailurePolicyAndRethrow(e);
      done = true;
    }
  }

  public void cancel(String reason) {}

  public boolean isDone() {
    return done;
  }

  public Optional<Payloads> getOutput() {
    return output;
  }

  public void close() {}

  public void handleSignal(
      String signalName,
      Optional<Payloads> input,
      long eventId,
      io.temporal.api.common.v1.Header header) {
    try {
      context.handleSignal(signalName, input, eventId, context.headerWithCodec(header));
    } catch (Throwable e) {
      applyWorkflowFailurePolicyAndRethrow(e);
    }
  }

  public Optional<Payloads> handleQuery(
      String type, io.temporal.api.common.v1.Header header, Optional<Payloads> args) {
    return context.handleQuery(type, context.headerWithCodec(header), args);
  }

  public void handleValidateUpdate(
      String updateName,
      String updateId,
      Optional<Payloads> input,
      long eventId,
      io.temporal.api.common.v1.Header header,
      DataConverter outcomeDataConverter) {
    try {
      context.handleValidateUpdate(
          updateName, updateId, input, eventId, context.headerWithCodec(header));
    } catch (Throwable e) {
      applyWorkflowFailurePolicyAndRethrow(e, outcomeDataConverter);
    }
  }

  public Optional<Payloads> handleExecuteUpdate(
      String updateName,
      String updateId,
      Optional<Payloads> input,
      long eventId,
      io.temporal.api.common.v1.Header header,
      DataConverter outcomeDataConverter) {
    try {
      return context.handleExecuteUpdate(
          updateName,
          updateId,
          input,
          eventId,
          context.headerWithCodec(header),
          outcomeDataConverter);
    } catch (UnsupportedContinueAsNewRequest e) {
      // Re-throw to fail the workflow task
      throw e;
    } catch (Throwable e) {
      applyWorkflowFailurePolicyAndRethrow(e, outcomeDataConverter);
    }
    return Optional.empty();
  }

  private void applyWorkflowFailurePolicyAndRethrow(Throwable e) {
    applyWorkflowFailurePolicyAndRethrow(e, null);
  }

  /**
   * @param updateOutcomeDataConverter the converter for the update's outcome if the exception was
   *     thrown by an update validator or handler, in which case the resulting failure rejects or
   *     fails the update rather than closing the workflow, or null otherwise
   */
  private void applyWorkflowFailurePolicyAndRethrow(
      Throwable e, @Nullable DataConverter updateOutcomeDataConverter) {
    if (e instanceof DestroyWorkflowThreadError) {
      throw (DestroyWorkflowThreadError) e;
    }
    Throwable exception = unwrap(e);

    Class<? extends Throwable>[] failTypes = implementationOptions.getFailWorkflowExceptionTypes();
    if (exception instanceof TemporalFailure) {
      throwAndFailWorkflowExecution(exception, updateOutcomeDataConverter);
    }
    for (Class<? extends Throwable> failType : failTypes) {
      if (failType.isAssignableFrom(exception.getClass())) {
        throwAndFailWorkflowExecution(exception, updateOutcomeDataConverter);
      }
    }

    throw wrap(exception);
  }

  private void throwAndFailWorkflowExecution(
      Throwable exception, @Nullable DataConverter updateOutcomeDataConverter) {
    ReplayWorkflowContext replayWorkflowContext = context.getReplayContext();
    @Nullable
    String fullReplayDirectQueryName = replayWorkflowContext.getFullReplayDirectQueryName();
    WorkflowInfo info = Workflow.getInfo();

    if (fullReplayDirectQueryName != null) {
      if (log.isDebugEnabled()
          && !requestedCancellation(replayWorkflowContext.isCancelRequested(), exception)) {
        log.debug(
            "Replayed workflow execution failure WorkflowId='{}', RunId={}, WorkflowType='{}' for direct query QueryType='{}'",
            info.getWorkflowId(),
            info.getRunId(),
            info.getWorkflowType(),
            fullReplayDirectQueryName,
            exception);
      }
    } else {
      if (log.isWarnEnabled()
          && !requestedCancellation(replayWorkflowContext.isCancelRequested(), exception)) {
        log.warn(
            "Workflow execution failure WorkflowId='{}', RunId={}, WorkflowType='{}'",
            info.getWorkflowId(),
            info.getRunId(),
            info.getWorkflowType(),
            exception);
      }
    }

    // An update failure belongs to the update, so it uses the update's own converter rather than
    // the one for the failure that closes the workflow.
    throw new WorkflowExecutionException(
        updateOutcomeDataConverter != null
            ? updateOutcomeDataConverter.exceptionToFailure(exception)
            : context.mapWorkflowExceptionToFailure(exception));
  }

  /**
   * @return true if both workflow cancellation is requested and the exception contains a
   *     cancellation exception in the chain
   */
  private boolean requestedCancellation(boolean cancelRequested, Throwable exception) {
    return cancelRequested && isCanceledCause(exception);
  }

  private static boolean isCanceledCause(Throwable exception) {
    while (exception != null) {
      if (exception instanceof CanceledFailure) {
        return true;
      }
      exception = exception.getCause();
    }
    return false;
  }
}
