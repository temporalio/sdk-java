package io.temporal.releaseautomation;

import static org.junit.Assert.assertEquals;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.client.WorkflowTargetOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.Test;

public class ReleaseWorkflowTest {
  @Test
  public void waitsIndefinitelyForExactUpdateThenPublishes() {
    ReleaseIdentity release = ReleaseFixtures.release();
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      Worker workflowWorker = environment.newWorker(QueueNames.releaseWorkflow(release));
      workflowWorker.registerWorkflowImplementationTypes(ReleaseWorkflowImpl.class);
      Worker publicationWorker = environment.newWorker(QueueNames.publication(release));
      publicationWorker.registerActivitiesImplementations(
          (PublicationActivities)
              input ->
                  new ReleaseResult(
                      input.release.digest(),
                      "https://github.example/release",
                      "https://central.example/artifact"));
      environment.start();

      String workflowId = QueueNames.releaseWorkflowId(release);
      ReleaseWorkflow starter =
          environment
              .getWorkflowClient()
              .newWorkflowStub(
                  ReleaseWorkflow.class,
                  WorkflowOptions.newBuilder()
                      .setWorkflowId(workflowId)
                      .setTaskQueue(QueueNames.releaseWorkflow(release))
                      .build());
      WorkflowExecution execution = WorkflowClient.start(starter::release, release);
      ReleaseWorkflow workflow =
          environment
              .getWorkflowClient()
              .newWorkflowStub(
                  ReleaseWorkflow.class,
                  WorkflowTargetOptions.newBuilder().setWorkflowExecution(execution).build());

      assertEquals("AWAITING_APPROVAL", workflow.status().phase);
      ApprovalEvidence approval =
          new ApprovalEvidence(
              CandidateIdentity.REPOSITORY,
              release.digest(),
              workflowId,
              execution.getRunId(),
              100,
              "release-manager",
              "abcdefabcdefabcdefabcdefabcdefabcdefabcd");
      workflow.approve(approval);
      ReleaseResult result = WorkflowStub.fromTyped(workflow).getResult(ReleaseResult.class);
      assertEquals(release.digest(), result.releaseDigest);
    }
  }
}
