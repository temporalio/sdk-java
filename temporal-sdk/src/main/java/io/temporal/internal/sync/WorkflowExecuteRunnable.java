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
import io.temporal.failure.FailureConverter;
import io.temporal.failure.TemporalFailure;
import io.temporal.internal.replay.ReplayWorkflowContext;
import io.temporal.internal.worker.WorkflowExecutionException;
import io.temporal.worker.WorkflowImplementationOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInfo;
import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class WorkflowExecuteRunnable implements Runnable {

  private static final Logger log = LoggerFactory.getLogger(WorkflowExecuteRunnable.class);

  private final SyncWorkflowContext context;
  private final SyncWorkflowDefinition workflow;
  private final WorkflowExecutionStartedEventAttributes attributes;
  private final WorkflowImplementationOptions implementationOptions;

  private Optional<Payloads> output = Optional.empty();
  private boolean done;

  public WorkflowExecuteRunnable(
      SyncWorkflowContext context,
      SyncWorkflowDefinition workflow,
      WorkflowExecutionStartedEventAttributes attributes,
      WorkflowImplementationOptions options) {
    this.implementationOptions = options;
    this.context = Objects.requireNonNull(context);
    this.workflow = Objects.requireNonNull(workflow);
    this.attributes = Objects.requireNonNull(attributes);
  }

  @Override
  public void run() {
    try {
      Optional<Payloads> input =
          attributes.hasInput() ? Optional.of(attributes.getInput()) : Optional.empty();
      output = workflow.execute(new Header(attributes.getHeader()), input);
    } catch (Throwable e) {
      if (e instanceof DestroyWorkflowThreadError) {
        throw (DestroyWorkflowThreadError) e;
      }
      Throwable exception = unwrap(e);

      Class<? extends Throwable>[] failTypes =
          implementationOptions.getFailWorkflowExceptionTypes();
      if (exception instanceof TemporalFailure) {
        throwAndFailWorkflowExecution(exception);
      }
      for (Class<? extends Throwable> failType : failTypes) {
        if (failType.isAssignableFrom(exception.getClass())) {
          throwAndFailWorkflowExecution(exception);
        }
      }
      throw wrap(exception);
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

  public void handleSignal(String signalName, Optional<Payloads> input, long eventId) {
    context.handleSignal(signalName, input, eventId);
  }

  public Optional<Payloads> handleQuery(String type, Optional<Payloads> args) {
    return context.handleQuery(type, args);
  }

  private void throwAndFailWorkflowExecution(Throwable exception) {
    ReplayWorkflowContext replayWorkflowContext = context.getContext();
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

    throw new WorkflowExecutionException(
        FailureConverter.exceptionToFailure(exception, context.getDataConverter()));
  }

  /**
   * @return true if both workflow cancellation is requested and the exception contains a
   *     cancellation exception in the chain
   */
  private boolean requestedCancellation(boolean cancelRequested, Throwable exception) {
    return cancelRequested && FailureConverter.isCanceledCause(exception);
  }
}
