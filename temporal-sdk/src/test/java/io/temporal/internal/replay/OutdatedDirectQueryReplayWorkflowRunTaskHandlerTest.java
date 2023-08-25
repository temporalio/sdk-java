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

package io.temporal.internal.replay;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.uber.m3.tally.NoopScope;
import io.temporal.activity.LocalActivityOptions;
import io.temporal.api.command.v1.StartTimerCommandAttributes;
import io.temporal.api.common.v1.ActivityType;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.History;
import io.temporal.api.query.v1.WorkflowQuery;
import io.temporal.api.workflowservice.v1.GetSystemInfoResponse;
import io.temporal.api.workflowservice.v1.PollActivityTaskQueueResponse;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponseOrBuilder;
import io.temporal.client.WorkflowStub;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.internal.Issue;
import io.temporal.internal.common.WorkflowExecutionUtils;
import io.temporal.internal.history.LocalActivityMarkerUtils;
import io.temporal.internal.statemachines.ExecuteLocalActivityParameters;
import io.temporal.internal.statemachines.WorkflowStateMachines;
import io.temporal.internal.worker.LocalActivityDispatcher;
import io.temporal.internal.worker.SingleWorkerOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkflowImplementationOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mockito;

@Issue("https://github.com/temporalio/sdk-java/issues/1371")
public class OutdatedDirectQueryReplayWorkflowRunTaskHandlerTest {
  private static final String QUERY_RESULT = "queryIsDone";
  private static final DataConverter dataConverter = DefaultDataConverter.STANDARD_INSTANCE;

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(WorkflowWithLocalActivityInASecondWorkflowTask.class)
          .setActivityImplementations(new TestActivities.TestActivitiesImpl())
          .build();

  private WorkflowStateMachines stateMachines;

  @Test
  public void queryIsOutdated() throws Throwable {
    TestWorkflows.TestWorkflowWithQuery noArgsWorkflow =
        testWorkflowRule.newWorkflowStub(TestWorkflows.TestWorkflowWithQuery.class);
    noArgsWorkflow.execute();
    // check that a normal query works
    assertEquals("A normal query should work", QUERY_RESULT, noArgsWorkflow.query());
    WorkflowExecution workflowExecution = WorkflowStub.fromTyped(noArgsWorkflow).getExecution();

    WorkflowExecutionHistory workflowExecutionHistory =
        testWorkflowRule.getWorkflowClient().fetchHistory(workflowExecution.getWorkflowId());

    PollWorkflowTaskQueueResponseOrBuilder wft =
        PollWorkflowTaskQueueResponse.newBuilder()
            .setWorkflowExecution(workflowExecution)
            .setPreviousStartedEventId(
                WorkflowExecutionUtils.getEventsOfType(
                        workflowExecutionHistory.getHistory(),
                        EventType.EVENT_TYPE_WORKFLOW_TASK_STARTED)
                    .get(0)
                    .getEventId())
            .setHistory(workflowExecutionHistory.getHistory())
            .setQuery(WorkflowQuery.newBuilder().setQueryType("some-query").build());

    ReplayWorkflowRunTaskHandler handler =
        new ReplayWorkflowRunTaskHandler(
            "UnitTest",
            createReplayWorkflow(workflowExecutionHistory),
            wft,
            SingleWorkerOptions.newBuilder().build(),
            new NoopScope(),
            mock(LocalActivityDispatcher.class),
            GetSystemInfoResponse.Capabilities.newBuilder().build());

    stateMachines = handler.getWorkflowStateMachines();
    QueryResult queryResult =
        handler.handleDirectQueryWorkflowTask(
            wft, new FullHistoryIterator(workflowExecutionHistory.getEvents()));
    assertEquals(
        QUERY_RESULT,
        dataConverter.fromPayloads(
            0, queryResult.getResponsePayloads(), String.class, String.class));
  }

  private ReplayWorkflow createReplayWorkflow(WorkflowExecutionHistory workflowExecutionHistory) {
    History history = workflowExecutionHistory.getHistory();
    ReplayWorkflow workflowMock = mock(ReplayWorkflow.class);
    // this mocks a ReplayWorkflow that does exactly the same as
    // WorkflowWithLocalActivityInASecondWorkflowTask with the state machines
    // TODO if we need to write more tests like this, we should consider creating a helper function
    //  that puts together a full working ReplayWorkflow entity from a workflow class
    Mockito.doAnswer(
            invocation -> {
              stateMachines.newTimer(
                  StartTimerCommandAttributes.newBuilder()
                      .setTimerId(
                          WorkflowExecutionUtils.getEventOfType(
                                  history, EventType.EVENT_TYPE_TIMER_STARTED)
                              .getTimerStartedEventAttributes()
                              .getTimerId())
                      .build(),
                  historyEvent -> {});
              return false;
            })
        .doAnswer(
            invocation -> {
              stateMachines.scheduleLocalActivityTask(
                  new ExecuteLocalActivityParameters(
                      PollActivityTaskQueueResponse.newBuilder()
                          .setActivityId(
                              LocalActivityMarkerUtils.getActivityId(
                                  WorkflowExecutionUtils.getEventOfType(
                                          history, EventType.EVENT_TYPE_MARKER_RECORDED)
                                      .getMarkerRecordedEventAttributes()))
                          .setActivityType(
                              ActivityType.newBuilder()
                                  .setName(
                                      LocalActivityMarkerUtils.getActivityTypeName(
                                          WorkflowExecutionUtils.getEventOfType(
                                                  history, EventType.EVENT_TYPE_MARKER_RECORDED)
                                              .getMarkerRecordedEventAttributes()))
                                  .build()),
                      null,
                      System.currentTimeMillis(),
                      null,
                      false,
                      null),
                  (r, e) -> {});
              return false;
            })
        .doAnswer(invocation -> true)
        .when(workflowMock)
        .eventLoop();

    Mockito.doReturn(dataConverter.toPayloads(QUERY_RESULT)).when(workflowMock).query(any());

    // This is needed just to the exception path inside ReplayWorkflowRunTaskHandler to work
    WorkflowContext context = mock(WorkflowContext.class);
    when(context.getWorkflowImplementationOptions())
        .thenReturn(WorkflowImplementationOptions.getDefaultInstance());
    when(context.mapWorkflowExceptionToFailure(any(Exception.class)))
        .then(invocation -> dataConverter.exceptionToFailure(invocation.getArgument(0)));
    when(workflowMock.getWorkflowContext()).thenReturn(context);

    return workflowMock;
  }

  public static class WorkflowWithLocalActivityInASecondWorkflowTask
      implements TestWorkflows.TestWorkflowWithQuery {
    @Override
    public String execute() {
      Workflow.sleep(1000); // end of the first workflow task
      TestActivities.VariousTestActivities activities =
          Workflow.newLocalActivityStub(
              TestActivities.VariousTestActivities.class,
              LocalActivityOptions.newBuilder()
                  .setStartToCloseTimeout(Duration.ofSeconds(10))
                  .build());
      activities.sleepActivity(100, 0);
      return "done";
    }

    @Override
    public String query() {
      return QUERY_RESULT;
    }
  }
}
