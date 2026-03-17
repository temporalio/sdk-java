package io.temporal.testserver.functional;

import static java.util.UUID.randomUUID;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.common.v1.Callback;
import io.temporal.api.common.v1.Link;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.common.v1.WorkflowType;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.enums.v1.WorkflowIdConflictPolicy;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.WorkflowExecutionOptionsUpdatedEventAttributes;
import io.temporal.api.taskqueue.v1.TaskQueue;
import io.temporal.api.workflow.v1.OnConflictOptions;
import io.temporal.api.workflow.v1.RequestIdInfo;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.StartWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.StartWorkflowExecutionResponse;
import io.temporal.client.*;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.internal.testservice.ExecutionId;
import io.temporal.internal.testservice.NexusOperationRef;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.testserver.functional.common.TestWorkflows;
import io.temporal.workflow.Workflow;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class WorkflowIdConflictPolicyTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(SignalWorkflowImpl.class).build();

  @Test
  public void conflictPolicyUseExisting() {
    String workflowId = "conflict-policy-use-existing-" + randomUUID();
    String requestId = randomUUID().toString();

    // Start workflow
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowId(workflowId)
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .setRequestId(requestId)
            .build();
    TestWorkflows.WorkflowWithSignal workflowStub =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(TestWorkflows.WorkflowWithSignal.class, options);
    WorkflowExecution we = WorkflowClient.start(workflowStub::execute);

    StartWorkflowExecutionRequest request1 =
        StartWorkflowExecutionRequest.newBuilder()
            .setNamespace(testWorkflowRule.getWorkflowClient().getOptions().getNamespace())
            .setWorkflowId(workflowId)
            .setWorkflowType(WorkflowType.newBuilder().setName("WorkflowWithSignal"))
            .setTaskQueue(TaskQueue.newBuilder().setName(testWorkflowRule.getTaskQueue()))
            .setRequestId(requestId)
            .setWorkflowIdConflictPolicy(
                WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING)
            .build();

    // Same request ID should return same response
    StartWorkflowExecutionResponse response1 =
        testWorkflowRule
            .getWorkflowClient()
            .getWorkflowServiceStubs()
            .blockingStub()
            .startWorkflowExecution(request1);

    Assert.assertTrue(response1.getStarted());
    Assert.assertEquals(we.getRunId(), response1.getRunId());
    Assert.assertEquals(
        Link.newBuilder()
            .setWorkflowEvent(
                Link.WorkflowEvent.newBuilder()
                    .setNamespace(testWorkflowRule.getWorkflowClient().getOptions().getNamespace())
                    .setWorkflowId(workflowId)
                    .setRunId(response1.getRunId())
                    .setEventRef(
                        Link.WorkflowEvent.EventReference.newBuilder()
                            .setEventId(1)
                            .setEventType(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED)))
            .build(),
        response1.getLink());

    // Different request ID should still work but update history
    String newRequestId = randomUUID().toString();
    NexusOperationRef ref =
        new NexusOperationRef(
            new ExecutionId(
                "some-random-namespace", "some-random-workflow-id", "some-random-run-id"),
            1);
    StartWorkflowExecutionRequest request2 =
        request1.toBuilder()
            .setRequestId(newRequestId)
            .setOnConflictOptions(
                OnConflictOptions.newBuilder()
                    .setAttachRequestId(true)
                    .setAttachCompletionCallbacks(true)
                    .setAttachLinks(true))
            .addCompletionCallbacks(
                Callback.newBuilder()
                    .setNexus(
                        Callback.Nexus.newBuilder()
                            .setUrl("http://localhost:7243/test")
                            .putHeader("operation-reference", ref.toBytes().toStringUtf8())))
            .addLinks(
                Link.newBuilder()
                    .setWorkflowEvent(
                        Link.WorkflowEvent.newBuilder()
                            .setNamespace("some-random-namespace")
                            .setWorkflowId("some-random-workflow-id")
                            .setRunId("some-random-run-id")))
            .build();

    StartWorkflowExecutionResponse response2 =
        testWorkflowRule
            .getWorkflowClient()
            .getWorkflowServiceStubs()
            .blockingStub()
            .startWorkflowExecution(request2);

    Assert.assertFalse(response2.getStarted());
    Assert.assertEquals(we.getRunId(), response2.getRunId());
    Assert.assertEquals(
        Link.newBuilder()
            .setWorkflowEvent(
                Link.WorkflowEvent.newBuilder()
                    .setNamespace(testWorkflowRule.getWorkflowClient().getOptions().getNamespace())
                    .setWorkflowId(workflowId)
                    .setRunId(response2.getRunId())
                    .setRequestIdRef(
                        Link.WorkflowEvent.RequestIdReference.newBuilder()
                            .setRequestId(newRequestId)
                            .setEventType(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_OPTIONS_UPDATED)))
            .build(),
        response2.getLink());

    // Same request ID should be deduped
    StartWorkflowExecutionResponse response3 =
        testWorkflowRule
            .getWorkflowClient()
            .getWorkflowServiceStubs()
            .blockingStub()
            .startWorkflowExecution(request2);

    Assert.assertFalse(response3.getStarted());
    Assert.assertEquals(we.getRunId(), response3.getRunId());
    Assert.assertEquals(
        Link.newBuilder()
            .setWorkflowEvent(
                Link.WorkflowEvent.newBuilder()
                    .setNamespace(testWorkflowRule.getWorkflowClient().getOptions().getNamespace())
                    .setWorkflowId(workflowId)
                    .setRunId(response3.getRunId())
                    .setRequestIdRef(
                        Link.WorkflowEvent.RequestIdReference.newBuilder()
                            .setRequestId(newRequestId)
                            .setEventType(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_OPTIONS_UPDATED)))
            .build(),
        response3.getLink());

    Map<String, RequestIdInfo> expectedRequestIds = new HashMap<>();
    expectedRequestIds.put(
        requestId,
        RequestIdInfo.newBuilder()
            .setEventType(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED)
            .setEventId(1)
            .setBuffered(false)
            .build());
    expectedRequestIds.put(
        newRequestId,
        RequestIdInfo.newBuilder()
            .setEventType(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_OPTIONS_UPDATED)
            .setEventId(0)
            .setBuffered(true)
            .build());
    describe(we).assertRequestIdInfos(expectedRequestIds);

    // Since the WorkflowExecutionOptionsUpdatedEvent is buffered, it won't show
    // up at this point because there a workflow task running. So, I'm signaling
    // the workflow so it will complete.
    workflowStub.signal();
    workflowStub.execute();

    WorkflowExecutionHistory history = testWorkflowRule.getExecutionHistory(workflowId);
    List<HistoryEvent> events =
        history.getEvents().stream()
            .filter(
                ev -> ev.getEventType() == EventType.EVENT_TYPE_WORKFLOW_EXECUTION_OPTIONS_UPDATED)
            .collect(Collectors.toList());
    Assert.assertEquals(1, events.size());
    HistoryEvent event = events.get(0);
    Assert.assertEquals(
        EventType.EVENT_TYPE_WORKFLOW_EXECUTION_OPTIONS_UPDATED, event.getEventType());
    WorkflowExecutionOptionsUpdatedEventAttributes attrs =
        event.getWorkflowExecutionOptionsUpdatedEventAttributes();
    Assert.assertEquals(newRequestId, attrs.getAttachedRequestId());
    Assert.assertEquals(1, attrs.getAttachedCompletionCallbacksCount());
    Assert.assertEquals(
        "http://localhost:7243/test", attrs.getAttachedCompletionCallbacks(0).getNexus().getUrl());
    Assert.assertEquals(1, event.getLinksCount());
    Assert.assertEquals(
        "some-random-namespace", event.getLinks(0).getWorkflowEvent().getNamespace());
    Assert.assertEquals(
        "some-random-workflow-id", event.getLinks(0).getWorkflowEvent().getWorkflowId());
    Assert.assertEquals("some-random-run-id", event.getLinks(0).getWorkflowEvent().getRunId());

    expectedRequestIds.put(
        newRequestId,
        RequestIdInfo.newBuilder()
            .setEventType(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_OPTIONS_UPDATED)
            .setEventId(event.getEventId())
            .setBuffered(false)
            .build());
    DescribeWorkflowAsserter asserter = describe(we);
    asserter.assertRequestIdInfos(expectedRequestIds);
    Assert.assertEquals(1, asserter.getActual().getCallbacksCount());
    Assert.assertEquals(
        "http://localhost:7243/test",
        asserter.getActual().getCallbacks(0).getCallback().getNexus().getUrl());
  }

  @Test
  public void conflictPolicyFail() {
    String workflowId = "conflict-policy-fail-" + randomUUID();
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowId(workflowId)
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .build();

    TestWorkflows.WorkflowWithSignal workflowStub =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(TestWorkflows.WorkflowWithSignal.class, options);
    WorkflowClient.start(workflowStub::execute);

    // Same workflow ID with conflict policy FAIL
    StartWorkflowExecutionRequest request1 =
        StartWorkflowExecutionRequest.newBuilder()
            .setNamespace(testWorkflowRule.getWorkflowClient().getOptions().getNamespace())
            .setWorkflowId(workflowId)
            .setWorkflowIdConflictPolicy(WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_FAIL)
            .setWorkflowType(WorkflowType.newBuilder().setName("WorkflowWithSignal"))
            .setTaskQueue(TaskQueue.newBuilder().setName(testWorkflowRule.getTaskQueue()))
            .build();

    StatusRuntimeException e =
        Assert.assertThrows(
            StatusRuntimeException.class,
            () ->
                testWorkflowRule
                    .getWorkflowClient()
                    .getWorkflowServiceStubs()
                    .blockingStub()
                    .startWorkflowExecution(request1));
    Assert.assertEquals(Status.Code.ALREADY_EXISTS, e.getStatus().getCode());

    // Setting OnConflictOptions should result in failure as well
    StartWorkflowExecutionRequest request2 =
        request1.toBuilder()
            .setOnConflictOptions(OnConflictOptions.newBuilder().setAttachRequestId(true).build())
            .build();

    // Should throw since OnConflictOptions only valid with USE_EXISTING
    e =
        Assert.assertThrows(
            StatusRuntimeException.class,
            () ->
                testWorkflowRule
                    .getWorkflowClient()
                    .getWorkflowServiceStubs()
                    .blockingStub()
                    .startWorkflowExecution(request2));
    Assert.assertEquals(Status.Code.ALREADY_EXISTS, e.getStatus().getCode());
  }

  private DescribeWorkflowAsserter describe(WorkflowExecution execution) {
    DescribeWorkflowAsserter result =
        new DescribeWorkflowAsserter(
            testWorkflowRule
                .getWorkflowClient()
                .getWorkflowServiceStubs()
                .blockingStub()
                .describeWorkflowExecution(
                    DescribeWorkflowExecutionRequest.newBuilder()
                        .setNamespace(
                            testWorkflowRule.getWorkflowClient().getOptions().getNamespace())
                        .setExecution(execution)
                        .build()));

    // There are some assertions that we can always make...
    return result
        .assertExecutionId(execution)
        .assertSaneTimestamps()
        .assertTaskQueue(testWorkflowRule.getTaskQueue());
  }

  public static class SignalWorkflowImpl implements TestWorkflows.WorkflowWithSignal {
    boolean unblock = false;

    @Override
    public void execute() {
      Workflow.await(() -> unblock);
    }

    @Override
    public void signal() {
      unblock = true;
    }
  }
}
