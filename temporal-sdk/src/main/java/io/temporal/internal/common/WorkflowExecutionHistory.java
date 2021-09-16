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

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.History;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.common.converter.DataConverterException;
import java.util.List;

/** Contains workflow execution ids and the history */
public final class WorkflowExecutionHistory {
  private final History history;

  public WorkflowExecutionHistory(History history) {
    checkHistory(history);
    this.history = history;
  }

  public static WorkflowExecutionHistory fromJson(String serialized) {
    String protoJson = HistoryJsonUtils.historyFormatJsonToProtoJson(serialized);

    JsonFormat.Parser parser = JsonFormat.parser();
    History.Builder historyBuilder = History.newBuilder();
    try {
      parser.merge(protoJson, historyBuilder);
    } catch (InvalidProtocolBufferException e) {
      throw new DataConverterException(e);
    }
    History history = historyBuilder.build();
    checkHistory(history);
    return new WorkflowExecutionHistory(history);
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

  public String toJson() {
    JsonFormat.Printer printer = JsonFormat.printer();
    try {
      String protoJson = printer.print(history);
      return HistoryJsonUtils.protoJsonToHistoryFormatJson(protoJson);
    } catch (InvalidProtocolBufferException e) {
      throw new DataConverterException(e);
    }
  }

  public WorkflowExecution getWorkflowExecution() {
    return WorkflowExecution.newBuilder()
        .setWorkflowId("workflow_id_in_replay")
        .setRunId("run_id_in_replay")
        .build();
  }

  public List<HistoryEvent> getEvents() {
    return history.getEventsList();
  }

  @Override
  public String toString() {
    return "WorkflowExecutionHistory{" + "history=" + history + '}';
  }
}
