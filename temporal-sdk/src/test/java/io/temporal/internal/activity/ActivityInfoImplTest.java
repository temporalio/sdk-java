package io.temporal.internal.activity;

import static org.junit.Assert.*;

import com.google.protobuf.ByteString;
import io.temporal.api.common.v1.ActivityType;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.workflowservice.v1.PollActivityTaskQueueResponse;
import io.temporal.internal.common.ProtobufTimeUtils;
import java.time.Instant;
import org.junit.Test;

/** Verifies that {@link ActivityInfoImpl} correctly handles standalone vs workflow-hosted tasks. */
public class ActivityInfoImplTest {

  private static final String NAMESPACE = "test-ns";

  private PollActivityTaskQueueResponse.Builder baseResponse() {
    return PollActivityTaskQueueResponse.newBuilder()
        .setTaskToken(ByteString.copyFromUtf8("token"))
        .setActivityId("act-123")
        .setActivityType(ActivityType.newBuilder().setName("MyActivity"))
        .setScheduledTime(ProtobufTimeUtils.toProtoTimestamp(Instant.EPOCH))
        .setCurrentAttemptScheduledTime(ProtobufTimeUtils.toProtoTimestamp(Instant.EPOCH))
        .setStartedTime(ProtobufTimeUtils.toProtoTimestamp(Instant.EPOCH));
  }

  @Test
  public void testWorkflowActivityReturnsWorkflowInfo() {
    PollActivityTaskQueueResponse response =
        baseResponse()
            .setWorkflowExecution(
                WorkflowExecution.newBuilder().setWorkflowId("wf-id").setRunId("run-id").build())
            .setWorkflowType(
                io.temporal.api.common.v1.WorkflowType.newBuilder().setName("MyWorkflow"))
            .build();

    ActivityInfoImpl info = new ActivityInfoImpl(response, NAMESPACE, "task-queue", false, null);
    assertEquals("wf-id", info.getWorkflowId());
    assertEquals("run-id", info.getWorkflowRunId());
    assertEquals("MyWorkflow", info.getWorkflowType());
    assertTrue(info.isInWorkflow());
  }

  @Test
  public void testStandaloneActivityReturnsNullWorkflowInfo() {
    // No workflowExecution set
    PollActivityTaskQueueResponse response = baseResponse().build();

    ActivityInfoImpl info = new ActivityInfoImpl(response, NAMESPACE, "task-queue", false, null);
    assertNull(info.getWorkflowId());
    assertNull(info.getWorkflowRunId());
    assertNull(info.getWorkflowType());
    assertFalse(info.isInWorkflow());
  }

  @Test
  public void testEmptyWorkflowIdTreatedAsStandalone() {
    PollActivityTaskQueueResponse response =
        baseResponse()
            .setWorkflowExecution(
                WorkflowExecution.newBuilder().setWorkflowId("").setRunId("").build())
            .build();

    ActivityInfoImpl info = new ActivityInfoImpl(response, NAMESPACE, "task-queue", false, null);
    assertNull(info.getWorkflowId());
    assertNull(info.getWorkflowRunId());
    assertFalse(info.isInWorkflow());
  }

  @Test
  public void testEmptyActivityRunIdTreatedAsNull() {
    PollActivityTaskQueueResponse response = baseResponse().setActivityRunId("").build();
    ActivityInfoImpl info = new ActivityInfoImpl(response, NAMESPACE, "task-queue", false, null);
    assertNull(info.getActivityRunId());
  }

  @Test
  public void testEmptyWorkflowTypeTreatedAsNull() {
    PollActivityTaskQueueResponse response =
        baseResponse()
            .setWorkflowExecution(
                WorkflowExecution.newBuilder().setWorkflowId("wf-id").setRunId("run").build())
            .setWorkflowType(io.temporal.api.common.v1.WorkflowType.newBuilder().setName(""))
            .build();

    ActivityInfoImpl info = new ActivityInfoImpl(response, NAMESPACE, "task-queue", false, null);
    // workflowId is set, but workflowType is empty → getWorkflowType() returns null
    assertEquals("wf-id", info.getWorkflowId());
    assertNull(info.getWorkflowType());
  }
}
