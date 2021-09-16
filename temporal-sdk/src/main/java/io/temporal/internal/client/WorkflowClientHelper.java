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

package io.temporal.internal.client;

import static io.temporal.serviceclient.MetricsTag.METRICS_TAGS_CALL_OPTIONS_KEY;

import com.google.protobuf.ByteString;
import com.uber.m3.tally.Scope;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.workflow.v1.WorkflowExecutionInfo;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse;
import io.temporal.client.WorkflowClient;
import io.temporal.internal.common.WorkflowExecutionUtils;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.Iterator;

/**
 * Contains different methods that could but didn't become a part of the main {@link
 * WorkflowClient}, mostly because they shouldn't be a part of normal usage and exist for tests /
 * debug only.
 */
public final class WorkflowClientHelper {

  /** Returns workflow instance history in a human readable format. */
  public static String prettyPrintHistory(
      WorkflowServiceStubs service,
      String namespace,
      WorkflowExecution workflowExecution,
      Scope metricsScope) {
    return prettyPrintHistory(service, namespace, workflowExecution, true, metricsScope);
  }

  /**
   * Returns workflow instance history in a human readable format.
   *
   * @param showWorkflowTasks when set to false workflow task events (command events) are not
   *     included
   * @param metricsScope
   */
  public static String prettyPrintHistory(
      WorkflowServiceStubs service,
      String namespace,
      WorkflowExecution workflowExecution,
      boolean showWorkflowTasks,
      Scope metricsScope) {
    Iterator<HistoryEvent> events = getHistory(service, namespace, workflowExecution, metricsScope);
    return WorkflowExecutionUtils.prettyPrintHistory(events, showWorkflowTasks);
  }

  public static Iterator<HistoryEvent> getHistory(
      WorkflowServiceStubs service,
      String namespace,
      WorkflowExecution workflowExecution,
      Scope metricsScope) {
    return new Iterator<HistoryEvent>() {
      ByteString nextPageToken = ByteString.EMPTY;
      Iterator<HistoryEvent> current;

      {
        getNextPage();
      }

      @Override
      public boolean hasNext() {
        return current.hasNext() || !nextPageToken.isEmpty();
      }

      @Override
      public HistoryEvent next() {
        if (current.hasNext()) {
          return current.next();
        }
        getNextPage();
        return current.next();
      }

      private void getNextPage() {
        GetWorkflowExecutionHistoryResponse history =
            getHistoryPage(service, namespace, workflowExecution, nextPageToken, metricsScope);
        current = history.getHistory().getEventsList().iterator();
        nextPageToken = history.getNextPageToken();
      }
    };
  }

  public static GetWorkflowExecutionHistoryResponse getHistoryPage(
      WorkflowServiceStubs service,
      String namespace,
      WorkflowExecution workflowExecution,
      ByteString nextPageToken,
      Scope metricsScope) {
    GetWorkflowExecutionHistoryRequest getHistoryRequest =
        GetWorkflowExecutionHistoryRequest.newBuilder()
            .setNamespace(namespace)
            .setExecution(workflowExecution)
            .setNextPageToken(nextPageToken)
            .build();
    return service
        .blockingStub()
        .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
        .getWorkflowExecutionHistory(getHistoryRequest);
  }

  public static WorkflowExecutionInfo describeWorkflowInstance(
      WorkflowServiceStubs service,
      String namespace,
      WorkflowExecution workflowExecution,
      Scope metricsScope) {
    DescribeWorkflowExecutionRequest describeRequest =
        DescribeWorkflowExecutionRequest.newBuilder()
            .setNamespace(namespace)
            .setExecution(workflowExecution)
            .build();
    DescribeWorkflowExecutionResponse executionDetail =
        service
            .blockingStub()
            .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
            .describeWorkflowExecution(describeRequest);
    WorkflowExecutionInfo instanceMetadata = executionDetail.getWorkflowExecutionInfo();
    return instanceMetadata;
  }
}
