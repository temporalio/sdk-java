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

package io.temporal.internal.common;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.History;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.common.converter.DataConverterException;
import java.util.List;

// This class by mistake leaked into a public interface and is used by users, so it can't be deleted
// right away.
// To fix the mistake, it was republished in a public package as
// io.temporal.common.WorkflowExecutionHistory class
// that extends this class.
/**
 * @deprecated use {@link io.temporal.common.WorkflowExecutionHistory} instead.
 */
@Deprecated
public class WorkflowExecutionHistory {
  private static final Gson GSON_PRETTY_PRINTER = new GsonBuilder().setPrettyPrinting().create();
  // we stay on using the old API that uses a JsonParser instance instead of static methods
  // to give users a larger range of supported version
  @SuppressWarnings("deprecation")
  private static final JsonParser GSON_PARSER = new JsonParser();

  private final History history;
  private final String workflowId;

  public WorkflowExecutionHistory(History history) {
    this(history, "workflow_id_in_replay");
  }

  public WorkflowExecutionHistory(History history, String workflowId) {
    checkHistory(history);
    this.history = history;
    this.workflowId = workflowId;
  }

  public static io.temporal.common.WorkflowExecutionHistory fromJson(String serialized) {
    String protoJson = HistoryJsonUtils.historyFormatJsonToProtoJson(serialized);

    JsonFormat.Parser parser = JsonFormat.parser().ignoringUnknownFields();
    History.Builder historyBuilder = History.newBuilder();
    try {
      parser.merge(protoJson, historyBuilder);
    } catch (InvalidProtocolBufferException e) {
      throw new DataConverterException(e);
    }
    History history = historyBuilder.build();
    return new io.temporal.common.WorkflowExecutionHistory(history);
  }

  private static void checkHistory(History history) {
    List<HistoryEvent> events = history.getEventsList();
    if (events == null || events.size() == 0) {
      throw new IllegalArgumentException("Empty history");
    }
    HistoryEvent startedEvent = events.get(0);
    if (startedEvent.getEventType() != EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED) {
      throw new IllegalArgumentException(
          "First event is not WorkflowExecutionStarted but " + startedEvent);
    }
    if (!startedEvent.hasWorkflowExecutionStartedEventAttributes()) {
      throw new IllegalArgumentException("First event is corrupted");
    }
  }

  /**
   * @return full json that can be used for replay and which is compatible with tctl
   */
  public String toJson(boolean prettyPrint) {
    JsonFormat.Printer printer = JsonFormat.printer();
    try {
      String protoJson = printer.print(history);
      String historyFormatJson = HistoryJsonUtils.protoJsonToHistoryFormatJson(protoJson);

      if (prettyPrint) {
        @SuppressWarnings("deprecation")
        JsonElement je = GSON_PARSER.parse(historyFormatJson);
        return GSON_PRETTY_PRINTER.toJson(je);
      } else {
        return historyFormatJson;
      }
    } catch (InvalidProtocolBufferException e) {
      throw new DataConverterException(e);
    }
  }

  /**
   * Returns workflow instance history in a human readable format.
   *
   * @param showWorkflowTasks when set to false workflow task events (command events) are not
   *     included
   */
  public String toProtoText(boolean showWorkflowTasks) {
    return HistoryProtoTextUtils.toProtoText(history, showWorkflowTasks);
  }

  public WorkflowExecution getWorkflowExecution() {
    return WorkflowExecution.newBuilder()
        .setWorkflowId(workflowId)
        .setRunId("run_id_in_replay")
        .build();
  }

  public List<HistoryEvent> getEvents() {
    return history.getEventsList();
  }

  public HistoryEvent getLastEvent() {
    int eventsCount = history.getEventsCount();
    return eventsCount > 0 ? history.getEvents(eventsCount - 1) : null;
  }

  public History getHistory() {
    return history;
  }

  @Override
  public String toString() {
    return "WorkflowExecutionHistory{" + "history=" + history + '}';
  }
}
