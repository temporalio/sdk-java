/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package io.temporal.internal.common;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.TextFormat;
import io.temporal.api.command.v1.Command;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.enums.v1.RetryState;
import io.temporal.api.enums.v1.TimeoutType;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.HistoryEventOrBuilder;
import io.temporal.api.history.v1.WorkflowExecutionCanceledEventAttributes;
import io.temporal.api.history.v1.WorkflowExecutionCompletedEventAttributes;
import io.temporal.api.history.v1.WorkflowExecutionFailedEventAttributes;
import io.temporal.api.history.v1.WorkflowExecutionTerminatedEventAttributes;
import io.temporal.api.history.v1.WorkflowExecutionTimedOutEventAttributes;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponseOrBuilder;
import io.temporal.client.WorkflowFailedException;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.EncodedValues;
import io.temporal.failure.CanceledFailure;
import io.temporal.failure.TerminatedFailure;
import io.temporal.failure.TimeoutFailure;
import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;

/**
 * Convenience methods to be used by unit tests and during development. Intended to be a collection
 * of relatively small static utility methods.
 */
public class WorkflowExecutionUtils {

  /**
   * Indentation for history and commands pretty printing. Do not change it from 2 spaces. The gson
   * pretty printer has it hardcoded and changing it breaks the indentation of exception stack
   * traces.
   */
  private static final String INDENTATION = "  ";

  public static Optional<Payloads> getResultFromCloseEvent(
      WorkflowExecution workflowExecution,
      Optional<String> workflowType,
      HistoryEvent closeEvent,
      DataConverter converter) {
    if (closeEvent == null) {
      throw new IllegalStateException("Workflow is still running");
    }
    switch (closeEvent.getEventType()) {
      case EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED:
        WorkflowExecutionCompletedEventAttributes completedEventAttributes =
            closeEvent.getWorkflowExecutionCompletedEventAttributes();
        if (completedEventAttributes.hasResult()) {
          return Optional.of(completedEventAttributes.getResult());
        }
        return Optional.empty();
      case EVENT_TYPE_WORKFLOW_EXECUTION_CANCELED:
        String message = null;
        WorkflowExecutionCanceledEventAttributes attributes =
            closeEvent.getWorkflowExecutionCanceledEventAttributes();
        Optional<Payloads> details =
            attributes.hasDetails() ? Optional.of(attributes.getDetails()) : Optional.empty();
        throw new WorkflowFailedException(
            workflowExecution,
            workflowType.orElse(null),
            0,
            RetryState.RETRY_STATE_NON_RETRYABLE_FAILURE,
            new CanceledFailure("Workflow canceled", new EncodedValues(details, converter), null));
      case EVENT_TYPE_WORKFLOW_EXECUTION_FAILED:
        WorkflowExecutionFailedEventAttributes failed =
            closeEvent.getWorkflowExecutionFailedEventAttributes();
        throw new WorkflowExecutionFailedException(
            failed.getFailure(), failed.getWorkflowTaskCompletedEventId(), failed.getRetryState());
      case EVENT_TYPE_WORKFLOW_EXECUTION_TERMINATED:
        WorkflowExecutionTerminatedEventAttributes terminated =
            closeEvent.getWorkflowExecutionTerminatedEventAttributes();
        throw new WorkflowFailedException(
            workflowExecution,
            workflowType.orElse(null),
            0,
            RetryState.RETRY_STATE_NON_RETRYABLE_FAILURE,
            new TerminatedFailure(terminated.getReason(), null));
      case EVENT_TYPE_WORKFLOW_EXECUTION_TIMED_OUT:
        WorkflowExecutionTimedOutEventAttributes timedOut =
            closeEvent.getWorkflowExecutionTimedOutEventAttributes();
        throw new WorkflowFailedException(
            workflowExecution,
            workflowType.orElse(null),
            0,
            timedOut.getRetryState(),
            new TimeoutFailure(null, null, TimeoutType.TIMEOUT_TYPE_START_TO_CLOSE));
      default:
        throw new RuntimeException(
            "Workflow end state is not completed: "
                + WorkflowExecutionUtils.prettyPrintObject(closeEvent));
    }
  }

  public static boolean isWorkflowTaskClosedEvent(HistoryEventOrBuilder event) {
    return ((event != null)
        && (event.getEventType() == EventType.EVENT_TYPE_WORKFLOW_TASK_COMPLETED
            || event.getEventType() == EventType.EVENT_TYPE_WORKFLOW_TASK_FAILED
            || event.getEventType() == EventType.EVENT_TYPE_WORKFLOW_TASK_TIMED_OUT));
  }

  public static boolean isWorkflowExecutionClosedEvent(HistoryEventOrBuilder event) {
    return ((event != null)
        && (event.getEventType() == EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED
            || event.getEventType() == EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CANCELED
            || event.getEventType() == EventType.EVENT_TYPE_WORKFLOW_EXECUTION_FAILED
            || event.getEventType() == EventType.EVENT_TYPE_WORKFLOW_EXECUTION_TIMED_OUT
            || event.getEventType() == EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CONTINUED_AS_NEW
            || event.getEventType() == EventType.EVENT_TYPE_WORKFLOW_EXECUTION_TERMINATED));
  }

  public static boolean isWorkflowExecutionCompleteCommand(Command command) {
    return ((command != null)
        && (command.getCommandType() == CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION
            || command.getCommandType() == CommandType.COMMAND_TYPE_CANCEL_WORKFLOW_EXECUTION
            || command.getCommandType() == CommandType.COMMAND_TYPE_FAIL_WORKFLOW_EXECUTION
            || command.getCommandType()
                == CommandType.COMMAND_TYPE_CONTINUE_AS_NEW_WORKFLOW_EXECUTION));
  }

  public static boolean isActivityTaskClosedEvent(HistoryEvent event) {
    return ((event != null)
        && (event.getEventType() == EventType.EVENT_TYPE_ACTIVITY_TASK_COMPLETED
            || event.getEventType() == EventType.EVENT_TYPE_ACTIVITY_TASK_CANCELED
            || event.getEventType() == EventType.EVENT_TYPE_ACTIVITY_TASK_FAILED
            || event.getEventType() == EventType.EVENT_TYPE_ACTIVITY_TASK_TIMED_OUT));
  }

  public static boolean isExternalWorkflowClosedEvent(HistoryEvent event) {
    return ((event != null)
        && (event.getEventType() == EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_COMPLETED
            || event.getEventType() == EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_CANCELED
            || event.getEventType() == EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_FAILED
            || event.getEventType() == EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_TERMINATED
            || event.getEventType() == EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_TIMED_OUT));
  }

  public static WorkflowExecution getWorkflowIdFromExternalWorkflowCompletedEvent(
      HistoryEvent event) {
    if (event != null) {
      if (event.getEventType() == EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_COMPLETED) {
        return event.getChildWorkflowExecutionCompletedEventAttributes().getWorkflowExecution();
      } else if (event.getEventType() == EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_CANCELED) {
        return event.getChildWorkflowExecutionCanceledEventAttributes().getWorkflowExecution();
      } else if (event.getEventType() == EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_FAILED) {
        return event.getChildWorkflowExecutionFailedEventAttributes().getWorkflowExecution();
      } else if (event.getEventType() == EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_TERMINATED) {
        return event.getChildWorkflowExecutionTerminatedEventAttributes().getWorkflowExecution();
      } else if (event.getEventType() == EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_TIMED_OUT) {
        return event.getChildWorkflowExecutionTimedOutEventAttributes().getWorkflowExecution();
      }
    }

    return null;
  }

  public static String getId(HistoryEvent historyEvent) {
    String id = null;
    if (historyEvent != null) {
      if (historyEvent.getEventType()
          == EventType.EVENT_TYPE_START_CHILD_WORKFLOW_EXECUTION_FAILED) {
        id = historyEvent.getStartChildWorkflowExecutionFailedEventAttributes().getWorkflowId();
      }
    }

    return id;
  }

  public static String getFailureCause(HistoryEvent historyEvent) {
    String failureCause = null;
    if (historyEvent != null) {
      if (historyEvent.getEventType()
          == EventType.EVENT_TYPE_START_CHILD_WORKFLOW_EXECUTION_FAILED) {
        failureCause =
            historyEvent
                .getStartChildWorkflowExecutionFailedEventAttributes()
                .getCause()
                .toString();
        //            } else if (historyEvent.getEventType() ==
        // EventType.EVENT_TYPE_SIGNAL_EXTERNAL_WORKFLOW_EXECUTION_FAILED) {
        //                failureCause =
        // historyEvent.getSignalExternalWorkflowExecutionFailedEventAttributes().getCause();
      } else {
        failureCause = "Cannot extract failure cause from " + historyEvent.getEventType();
      }
    }

    return failureCause;
  }

  public static WorkflowExecutionStatus getCloseStatus(HistoryEvent event) {
    switch (event.getEventType()) {
      case EVENT_TYPE_WORKFLOW_EXECUTION_CANCELED:
        return WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_CANCELED;
      case EVENT_TYPE_WORKFLOW_EXECUTION_FAILED:
        return WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_FAILED;
      case EVENT_TYPE_WORKFLOW_EXECUTION_TIMED_OUT:
        return WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_TIMED_OUT;
      case EVENT_TYPE_WORKFLOW_EXECUTION_CONTINUED_AS_NEW:
        return WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_CONTINUED_AS_NEW;
      case EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED:
        return WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_COMPLETED;
      case EVENT_TYPE_WORKFLOW_EXECUTION_TERMINATED:
        return WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_TERMINATED;
      default:
        throw new IllegalArgumentException("Not a close event: " + event);
    }
  }

  public static String prettyPrintCommands(Iterable<Command> commands) {
    StringBuilder result = new StringBuilder();
    for (Command command : commands) {
      result.append(prettyPrintObject(command));
    }
    return result.toString();
  }

  /** Pretty prints a proto message. */
  @SuppressWarnings("deprecation")
  public static String prettyPrintObject(MessageOrBuilder object) {
    return TextFormat.printToString(object);
  }

  public static boolean containsEvent(List<HistoryEvent> history, EventType eventType) {
    for (HistoryEvent event : history) {
      if (event.getEventType() == eventType) {
        return true;
      }
    }
    return false;
  }

  private static void fixStackTrace(JsonElement json, String stackIndentation) {
    if (!json.isJsonObject()) {
      return;
    }
    for (Entry<String, JsonElement> entry : json.getAsJsonObject().entrySet()) {
      if ("stackTrace".equals(entry.getKey())) {
        String value = entry.getValue().getAsString();
        String replacement = "\n" + stackIndentation;
        String fixed = value.replaceAll("\\n", replacement);
        entry.setValue(new JsonPrimitive(fixed));
        continue;
      }
      fixStackTrace(entry.getValue(), stackIndentation + INDENTATION);
    }
  }

  /** Command event is an event that is created to mirror a command issued by a workflow task */
  public static boolean isCommandEvent(HistoryEvent event) {
    EventType eventType = event.getEventType();
    switch (eventType) {
      case EVENT_TYPE_ACTIVITY_TASK_SCHEDULED:
      case EVENT_TYPE_START_CHILD_WORKFLOW_EXECUTION_INITIATED:
      case EVENT_TYPE_TIMER_STARTED:
      case EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED:
      case EVENT_TYPE_WORKFLOW_EXECUTION_FAILED:
      case EVENT_TYPE_WORKFLOW_EXECUTION_CANCELED:
      case EVENT_TYPE_WORKFLOW_EXECUTION_CONTINUED_AS_NEW:
      case EVENT_TYPE_ACTIVITY_TASK_CANCEL_REQUESTED:
      case EVENT_TYPE_TIMER_CANCELED:
      case EVENT_TYPE_REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED:
      case EVENT_TYPE_MARKER_RECORDED:
      case EVENT_TYPE_SIGNAL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED:
      case EVENT_TYPE_UPSERT_WORKFLOW_SEARCH_ATTRIBUTES:
        return true;
      default:
        return false;
    }
  }

  /** Returns event that corresponds to a command. */
  public static EventType getEventTypeForCommand(CommandType commandType) {
    switch (commandType) {
      case COMMAND_TYPE_SCHEDULE_ACTIVITY_TASK:
        return EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED;
      case COMMAND_TYPE_REQUEST_CANCEL_ACTIVITY_TASK:
        return EventType.EVENT_TYPE_ACTIVITY_TASK_CANCEL_REQUESTED;
      case COMMAND_TYPE_START_TIMER:
        return EventType.EVENT_TYPE_TIMER_STARTED;
      case COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION:
        return EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED;
      case COMMAND_TYPE_FAIL_WORKFLOW_EXECUTION:
        return EventType.EVENT_TYPE_WORKFLOW_EXECUTION_FAILED;
      case COMMAND_TYPE_CANCEL_TIMER:
        return EventType.EVENT_TYPE_TIMER_CANCELED;
      case COMMAND_TYPE_CANCEL_WORKFLOW_EXECUTION:
        return EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CANCELED;
      case COMMAND_TYPE_REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION:
        return EventType.EVENT_TYPE_REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED;
      case COMMAND_TYPE_RECORD_MARKER:
        return EventType.EVENT_TYPE_MARKER_RECORDED;
      case COMMAND_TYPE_CONTINUE_AS_NEW_WORKFLOW_EXECUTION:
        return EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CONTINUED_AS_NEW;
      case COMMAND_TYPE_START_CHILD_WORKFLOW_EXECUTION:
        return EventType.EVENT_TYPE_START_CHILD_WORKFLOW_EXECUTION_INITIATED;
      case COMMAND_TYPE_SIGNAL_EXTERNAL_WORKFLOW_EXECUTION:
        return EventType.EVENT_TYPE_SIGNAL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED;
      case COMMAND_TYPE_UPSERT_WORKFLOW_SEARCH_ATTRIBUTES:
        return EventType.EVENT_TYPE_UPSERT_WORKFLOW_SEARCH_ATTRIBUTES;
    }
    throw new IllegalArgumentException("Unknown commandType");
  }

  public static boolean isFullHistory(PollWorkflowTaskQueueResponseOrBuilder workflowTask) {
    return workflowTask.getHistory() != null
        && workflowTask.getHistory().getEventsCount() > 0
        && workflowTask.getHistory().getEvents(0).getEventId() == 1;
  }
}
