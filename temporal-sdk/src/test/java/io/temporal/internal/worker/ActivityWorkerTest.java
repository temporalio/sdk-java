package io.temporal.internal.worker;

import static org.junit.Assert.assertEquals;

import io.temporal.api.common.v1.ActivityType;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.common.v1.WorkflowType;
import io.temporal.api.workflowservice.v1.PollActivityTaskQueueResponse;
import io.temporal.payload.storage.StorageDriverActivityInfo;
import io.temporal.payload.storage.StorageDriverTargetInfo;
import io.temporal.payload.storage.StorageDriverWorkflowInfo;
import org.junit.Test;

public class ActivityWorkerTest {

  @Test
  public void standaloneActivityTargetsTheActivity() {
    PollActivityTaskQueueResponse response =
        PollActivityTaskQueueResponse.newBuilder()
            .setActivityId("act-1")
            .setActivityRunId("run-1")
            .setActivityType(ActivityType.newBuilder().setName("MyActivity"))
            .build();

    StorageDriverTargetInfo target = ActivityWorker.storageTargetForActivityTask("ns", response);

    assertEquals(new StorageDriverActivityInfo("ns", "act-1", "run-1", "MyActivity"), target);
  }

  @Test
  public void workflowActivityTargetsTheWorkflow() {
    PollActivityTaskQueueResponse response =
        PollActivityTaskQueueResponse.newBuilder()
            .setActivityId("act-1")
            .setActivityType(ActivityType.newBuilder().setName("MyActivity"))
            .setWorkflowType(WorkflowType.newBuilder().setName("MyWorkflow"))
            .setWorkflowExecution(
                WorkflowExecution.newBuilder().setWorkflowId("wf-1").setRunId("wf-run-1"))
            .build();

    StorageDriverTargetInfo target = ActivityWorker.storageTargetForActivityTask("ns", response);

    assertEquals(new StorageDriverWorkflowInfo("ns", "wf-1", "wf-run-1", "MyWorkflow"), target);
  }
}
