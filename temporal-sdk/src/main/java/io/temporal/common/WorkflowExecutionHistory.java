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

package io.temporal.common;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.history.v1.History;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.common.converter.DataConverterException;
import io.temporal.internal.common.HistoryJsonUtils;
import java.util.List;

/**
 * Provides a wrapper with convenience methods over raw protobuf {@link History} object representing
 * workflow history
 */
@SuppressWarnings("deprecation")
public final class WorkflowExecutionHistory
    extends io.temporal.internal.common.WorkflowExecutionHistory {

  /**
   * @param history raw history object to enrich
   */
  public WorkflowExecutionHistory(History history) {
    super(history);
  }

  /**
   * WorkflowId is not persisted in workflow history, and sometimes it may be important to have it
   * set (workflow replay may rely on it if WorkflowExecutionHistory is used for a history replay)
   *
   * @param history raw history object to enrich
   * @param workflowId workflow id to be used in {@link #getWorkflowExecution()}
   */
  public WorkflowExecutionHistory(History history, String workflowId) {
    super(history, workflowId);
  }

  /**
   * @param serialized history json (tctl format) to import and deserialize into {@link History}
   * @return WorkflowExecutionHistory
   */
  public static WorkflowExecutionHistory fromJson(String serialized) {
    return fromJson(serialized, DEFAULT_WORKFLOW_ID);
  }

  /**
   * @param serialized history json (tctl format) to import and deserialize into {@link History}
   * @param workflowId workflow id to be used in {@link #getWorkflowExecution()}
   * @return WorkflowExecutionHistory
   */
  public static WorkflowExecutionHistory fromJson(String serialized, String workflowId) {
    String protoJson = HistoryJsonUtils.historyFormatJsonToProtoJson(serialized);

    JsonFormat.Parser parser = JsonFormat.parser().ignoringUnknownFields();
    History.Builder historyBuilder = History.newBuilder();
    try {
      parser.merge(protoJson, historyBuilder);
    } catch (InvalidProtocolBufferException e) {
      throw new DataConverterException(e);
    }
    History history = historyBuilder.build();
    return new WorkflowExecutionHistory(history, workflowId);
  }

  /**
   * @return full json that can be used for replay and which is compatible with tctl
   */
  public String toJson(boolean prettyPrint) {
    return super.toJson(prettyPrint);
  }

  /**
   * Returns workflow instance history in a human-readable format.
   *
   * @param showWorkflowTasks when set to false workflow task events (command events) are not
   *     included
   */
  public String toProtoText(boolean showWorkflowTasks) {
    return super.toProtoText(showWorkflowTasks);
  }

  @Override
  public WorkflowExecution getWorkflowExecution() {
    return super.getWorkflowExecution();
  }

  public List<HistoryEvent> getEvents() {
    return super.getEvents();
  }

  public HistoryEvent getLastEvent() {
    return super.getLastEvent();
  }

  public History getHistory() {
    return super.getHistory();
  }

  @Override
  public String toString() {
    return super.toString();
  }
}
