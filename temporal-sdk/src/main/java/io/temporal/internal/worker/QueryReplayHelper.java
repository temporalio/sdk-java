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

package io.temporal.internal.worker;

import com.google.protobuf.ByteString;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.WorkflowType;
import io.temporal.api.history.v1.History;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.WorkflowExecutionStartedEventAttributes;
import io.temporal.api.query.v1.WorkflowQuery;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse;
import io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest;
import io.temporal.internal.common.WorkflowExecutionHistory;
import java.util.List;
import java.util.Optional;

/**
 * Encapsulates a special query implementation for replaying workflow histories to support {@link
 * io.temporal.worker.Worker#replayWorkflowExecution}
 *
 * <p>The implementation in this class doesn't execute under runId lock used in the main code path
 * of Worker, so it shouldn't be using the workflow cache either.
 */
public class QueryReplayHelper {
  private final WorkflowTaskHandler handler;

  /**
   * @param nonStickyHandler it's important for this handler to be non-sticky. Otherwise, we will be
   *     working with workflow cache without obtaining a runId lock.
   */
  public QueryReplayHelper(WorkflowTaskHandler nonStickyHandler) {
    this.handler = nonStickyHandler;
  }

  public Optional<Payloads> queryWorkflowExecution(
      String jsonSerializedHistory, String queryType, Optional<Payloads> args) throws Exception {
    WorkflowExecutionHistory history = WorkflowExecutionHistory.fromJson(jsonSerializedHistory);
    return queryWorkflowExecution(queryType, args, history, ByteString.EMPTY);
  }

  public Optional<Payloads> queryWorkflowExecution(
      WorkflowExecutionHistory history, String queryType, Optional<Payloads> args)
      throws Exception {
    return queryWorkflowExecution(queryType, args, history, ByteString.EMPTY);
  }

  private Optional<Payloads> queryWorkflowExecution(
      String queryType,
      Optional<Payloads> args,
      WorkflowExecutionHistory history,
      ByteString nextPageToken)
      throws Exception {
    WorkflowQuery.Builder query = WorkflowQuery.newBuilder().setQueryType(queryType);
    args.ifPresent(query::setQueryArgs);
    PollWorkflowTaskQueueResponse.Builder task =
        PollWorkflowTaskQueueResponse.newBuilder()
            .setWorkflowExecution(history.getWorkflowExecution())
            .setStartedEventId(Long.MAX_VALUE)
            .setPreviousStartedEventId(Long.MAX_VALUE)
            .setNextPageToken(nextPageToken)
            .setQuery(query);
    List<HistoryEvent> events = history.getEvents();
    HistoryEvent startedEvent = events.get(0);
    if (!startedEvent.hasWorkflowExecutionStartedEventAttributes()) {
      throw new IllegalStateException(
          "First event of the history is not WorkflowExecutionStarted: " + startedEvent);
    }
    WorkflowExecutionStartedEventAttributes started =
        startedEvent.getWorkflowExecutionStartedEventAttributes();
    WorkflowType workflowType = started.getWorkflowType();
    task.setWorkflowType(workflowType);
    task.setHistory(History.newBuilder().addAllEvents(events));
    WorkflowTaskHandler.Result result = handler.handleWorkflowTask(task.build());
    if (result.getQueryCompleted() != null) {
      RespondQueryTaskCompletedRequest r = result.getQueryCompleted();
      if (!r.getErrorMessage().isEmpty()) {
        throw new RuntimeException(
            "query failure for "
                + history.getWorkflowExecution()
                + ", queryType="
                + queryType
                + ", args="
                + args
                + ", error="
                + r.getErrorMessage());
      }
      if (r.hasQueryResult()) {
        return Optional.of(r.getQueryResult());
      } else {
        return Optional.empty();
      }
    }
    throw new RuntimeException("Query returned wrong response: " + result);
  }
}
