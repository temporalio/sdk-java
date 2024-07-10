/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.temporal.internal.sync;

import static io.temporal.internal.sync.WorkflowInternal.unwrap;
import static io.temporal.serviceclient.CheckedExceptionWrapper.wrap;

import io.temporal.api.common.v1.Payloads;
import io.temporal.api.history.v1.WorkflowExecutionStartedEventAttributes;
import io.temporal.common.interceptors.Header;
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
      output = workflow.execute(new Header(attributes.getHeader()), input);
    } catch (Throwable e) {
      applyWorkflowFailurePolicyAndRethrow(e);
    } finally {
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
      context.handleSignal(signalName, input, eventId, new Header(header));
    } catch (Throwable e) {
      applyWorkflowFailurePolicyAndRethrow(e);
    }
  }

  public Optional<Payloads> handleQuery(
      String type, io.temporal.api.common.v1.Header header, Optional<Payloads> args) {
    return context.handleQuery(type, new Header(header), args);
  }

  public void handleValidateUpdate(
      String updateName,
      String updateId,
      Optional<Payloads> input,
      long eventId,
      io.temporal.api.common.v1.Header header) {
    try {
      context.handleValidateUpdate(updateName, updateId, input, eventId, new Header(header));
    } catch (Throwable e) {
      applyWorkflowFailurePolicyAndRethrow(e);
    }
  }

  public Optional<Payloads> handleExecuteUpdate(
      String updateName,
      String updateId,
      Optional<Payloads> input,
      long eventId,
      io.temporal.api.common.v1.Header header) {
    try {
      return context.handleExecuteUpdate(updateName, updateId, input, eventId, new Header(header));
    } catch (UnsupportedContinueAsNewRequest e) {
      // Re-throw to fail the workflow task
      throw e;
    } catch (Throwable e) {
      applyWorkflowFailurePolicyAndRethrow(e);
    }
    return Optional.empty();
  }

  private void applyWorkflowFailurePolicyAndRethrow(Throwable e) {
    if (e instanceof DestroyWorkflowThreadError) {
      throw (DestroyWorkflowThreadError) e;
    }
    Throwable exception = unwrap(e);

    Class<? extends Throwable>[] failTypes = implementationOptions.getFailWorkflowExceptionTypes();
    if (exception instanceof TemporalFailure) {
      throwAndFailWorkflowExecution(exception);
    }
    for (Class<? extends Throwable> failType : failTypes) {
      if (failType.isAssignableFrom(exception.getClass())) {
        throwAndFailWorkflowExecution(exception);
      }
    }

    throw wrap(exception);
  }

  private void throwAndFailWorkflowExecution(Throwable exception) {
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

    throw new WorkflowExecutionException(context.mapWorkflowExceptionToFailure(exception));
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
