package io.temporal.internal.testservice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.protobuf.ByteString;
import com.google.protobuf.util.Durations;
import io.temporal.api.command.v1.Command;
import io.temporal.api.command.v1.ContinueAsNewWorkflowExecutionCommandAttributes;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.Priority;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.common.v1.WorkflowType;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.enums.v1.QueryResultType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.query.v1.WorkflowQuery;
import io.temporal.api.taskqueue.v1.TaskQueue;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueRequest;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse;
import io.temporal.api.workflowservice.v1.QueryWorkflowRequest;
import io.temporal.api.workflowservice.v1.QueryWorkflowResponse;
import io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest;
import io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedRequest;
import io.temporal.api.workflowservice.v1.StartWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.StartWorkflowExecutionResponse;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import java.time.Clock;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class TestWorkflowMutableStateImplTest {

  @Test(timeout = 60000)
  public void queryBufferedDuringContinueAsNewIsDispatchedToClosedRun() throws Exception {
    SelfAdvancingTimerImpl timer = new SelfAdvancingTimerImpl(0, Clock.systemUTC());
    timer.lockTimeSkipping("query test");
    TestWorkflowStoreImpl store = spy(new TestWorkflowStoreImpl(timer));
    TestWorkflowService service =
        new TestWorkflowService(
            store, new TestVisibilityStoreImpl(), new TestNexusEndpointStoreImpl(), timer);
    InProcessGRPCServer server = new InProcessGRPCServer(Collections.singletonList(service));
    ListenableFuture<QueryWorkflowResponse> queryFuture = null;
    try {
      WorkflowServiceGrpc.WorkflowServiceBlockingStub blockingStub =
          WorkflowServiceGrpc.newBlockingStub(Objects.requireNonNull(server.getChannel()));
      WorkflowServiceGrpc.WorkflowServiceFutureStub futureStub =
          WorkflowServiceGrpc.newFutureStub(Objects.requireNonNull(server.getChannel()));
      String namespace = "query-test";
      String workflowId = "query-during-continue-as-new";
      TaskQueue originalTaskQueue = TaskQueue.newBuilder().setName("original-run").build();
      TaskQueue continuedTaskQueue = TaskQueue.newBuilder().setName("continued-run").build();
      StartWorkflowExecutionResponse startResponse =
          blockingStub
              .withDeadlineAfter(5, TimeUnit.SECONDS)
              .startWorkflowExecution(
                  StartWorkflowExecutionRequest.newBuilder()
                      .setNamespace(namespace)
                      .setWorkflowId(workflowId)
                      .setWorkflowType(WorkflowType.newBuilder().setName("QueryWorkflow"))
                      .setTaskQueue(originalTaskQueue)
                      .setRequestId(UUID.randomUUID().toString())
                      .setWorkflowTaskTimeout(Durations.fromSeconds(60))
                      .build());
      WorkflowExecution originalExecution =
          WorkflowExecution.newBuilder()
              .setWorkflowId(workflowId)
              .setRunId(startResponse.getRunId())
              .build();
      PollWorkflowTaskQueueResponse workflowTask =
          blockingStub
              .withDeadlineAfter(5, TimeUnit.SECONDS)
              .pollWorkflowTaskQueue(
                  PollWorkflowTaskQueueRequest.newBuilder()
                      .setNamespace(namespace)
                      .setTaskQueue(originalTaskQueue)
                      .build());
      assertEquals(originalExecution, workflowTask.getWorkflowExecution());

      CountDownLatch queryBuffered = new CountDownLatch(1);
      // No other work is submitted before this latch fires, so this observes the query buffer.
      doAnswer(
              invocation -> {
                invocation.callRealMethod();
                queryBuffered.countDown();
                return null;
              })
          .when(store)
          .applyTimersAndLocks(any(RequestContext.class));

      Payloads queryAnswer =
          Payloads.newBuilder()
              .addPayloads(Payload.newBuilder().setData(ByteString.copyFromUtf8("original-run")))
              .build();
      AtomicReference<PollWorkflowTaskQueueResponse> dispatchedQuery = new AtomicReference<>();
      // Complete the query before dispatch returns to exercise the fastest possible worker reply.
      doAnswer(
              invocation -> {
                invocation.callRealMethod();
                PollWorkflowTaskQueueResponse.Builder queryTask = invocation.getArgument(2);
                dispatchedQuery.set(queryTask.build());
                blockingStub
                    .withDeadlineAfter(5, TimeUnit.SECONDS)
                    .respondQueryTaskCompleted(
                        RespondQueryTaskCompletedRequest.newBuilder()
                            .setTaskToken(queryTask.getTaskToken())
                            .setCompletedType(QueryResultType.QUERY_RESULT_TYPE_ANSWERED)
                            .setQueryResult(queryAnswer)
                            .build());
                return null;
              })
          .when(store)
          .sendQueryTask(
              any(ExecutionId.class),
              any(TestWorkflowStore.TaskQueueId.class),
              any(PollWorkflowTaskQueueResponse.Builder.class),
              any(Priority.class));

      queryFuture =
          futureStub
              .withDeadlineAfter(20, TimeUnit.SECONDS)
              .queryWorkflow(
                  QueryWorkflowRequest.newBuilder()
                      .setNamespace(namespace)
                      .setExecution(WorkflowExecution.newBuilder().setWorkflowId(workflowId))
                      .setQuery(WorkflowQuery.newBuilder().setQueryType("state"))
                      .build());
      assertTrue("Query was not buffered", queryBuffered.await(5, TimeUnit.SECONDS));

      blockingStub
          .withDeadlineAfter(5, TimeUnit.SECONDS)
          .respondWorkflowTaskCompleted(
              RespondWorkflowTaskCompletedRequest.newBuilder()
                  .setTaskToken(workflowTask.getTaskToken())
                  .addCommands(
                      Command.newBuilder()
                          .setCommandType(
                              CommandType.COMMAND_TYPE_CONTINUE_AS_NEW_WORKFLOW_EXECUTION)
                          .setContinueAsNewWorkflowExecutionCommandAttributes(
                              ContinueAsNewWorkflowExecutionCommandAttributes.newBuilder()
                                  .setTaskQueue(continuedTaskQueue)))
                  .build());

      assertEquals(queryAnswer, queryFuture.get(5, TimeUnit.SECONDS).getQueryResult());
      PollWorkflowTaskQueueResponse queryTask = dispatchedQuery.get();
      assertNotNull("Query was not dispatched", queryTask);
      assertEquals(originalExecution, queryTask.getWorkflowExecution());
      assertTrue(queryTask.hasQuery());
      List<HistoryEvent> history = queryTask.getHistory().getEventsList();
      HistoryEvent closeEvent = history.get(history.size() - 1);
      assertEquals(
          EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CONTINUED_AS_NEW, closeEvent.getEventType());
      assertEquals(
          1,
          history.stream()
              .filter(event -> event.getEventType() == EventType.EVENT_TYPE_WORKFLOW_TASK_SCHEDULED)
              .count());

      String continuedRunId =
          closeEvent.getWorkflowExecutionContinuedAsNewEventAttributes().getNewExecutionRunId();
      assertFalse(continuedRunId.isEmpty());
      assertNotEquals(originalExecution.getRunId(), continuedRunId);
      PollWorkflowTaskQueueResponse continuedTask =
          blockingStub
              .withDeadlineAfter(5, TimeUnit.SECONDS)
              .pollWorkflowTaskQueue(
                  PollWorkflowTaskQueueRequest.newBuilder()
                      .setNamespace(namespace)
                      .setTaskQueue(continuedTaskQueue)
                      .build());
      assertEquals(continuedRunId, continuedTask.getWorkflowExecution().getRunId());
      assertFalse(continuedTask.hasQuery());
    } finally {
      if (queryFuture != null) {
        queryFuture.cancel(true);
      }
      server.shutdownNow();
      service.close();
      server.awaitTermination(5, TimeUnit.SECONDS);
    }
  }
}
