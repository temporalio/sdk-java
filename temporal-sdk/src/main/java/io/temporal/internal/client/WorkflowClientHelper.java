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

package io.temporal.internal.client;

import static io.temporal.serviceclient.MetricsTag.METRICS_TAGS_CALL_OPTIONS_KEY;

import com.google.protobuf.ByteString;
import com.uber.m3.tally.Scope;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.workflow.v1.WorkflowExecutionInfo;
import io.temporal.api.workflowservice.v1.*;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.Iterator;
import javax.annotation.Nullable;

/**
 * Contains different methods that could but didn't become a part of the main {@link
 * WorkflowClient}, mostly because they shouldn't be a part of normal usage and exist for tests /
 * debug only.
 */
public final class WorkflowClientHelper {
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
      @Nullable Scope metricsScope) {
    DescribeWorkflowExecutionRequest describeRequest =
        DescribeWorkflowExecutionRequest.newBuilder()
            .setNamespace(namespace)
            .setExecution(workflowExecution)
            .build();
    WorkflowServiceGrpc.WorkflowServiceBlockingStub stub = service.blockingStub();
    if (metricsScope != null) {
      stub = stub.withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope);
    }
    DescribeWorkflowExecutionResponse executionDetail =
        stub.describeWorkflowExecution(describeRequest);
    WorkflowExecutionInfo instanceMetadata = executionDetail.getWorkflowExecutionInfo();
    return instanceMetadata;
  }
}
