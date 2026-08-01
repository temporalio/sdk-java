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
          new PublicationActivities() {
            @Override
            public void preflight(PublicationInput input) {}

            @Override
            public String reconcileMaven(PublicationInput input) {
              return "https://central.example/artifact";
            }

            @Override
            public String reconcileGithubDraft(PublicationInput input) {
              return "https://github.example/draft";
            }

            @Override
            public ReleaseResult publishGithubRelease(
                PublicationInput input, String mavenCentralUrl) {
              return new ReleaseResult(
                  input.release.digest(), "https://github.example/release", mavenCentralUrl);
            }
          });
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
      workflow.requestApproval(
          new ApprovalRequest(
              CandidateIdentity.REPOSITORY,
              release.digest(),
              workflowId,
              execution.getRunId(),
              100,
              42,
              "ISSUE_node_42",
              "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
              release.candidate.trustedAutomationCommit));
      ApprovalEvidence approval =
          new ApprovalEvidence(
              CandidateIdentity.REPOSITORY,
              release.digest(),
              workflowId,
              execution.getRunId(),
              100,
              "release-manager",
              42,
              "ISSUE_node_42",
              "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
              release.candidate.trustedAutomationCommit);
      workflow.approve(approval);
      ReleaseResult result = WorkflowStub.fromTyped(workflow).getResult(ReleaseResult.class);
      assertEquals(release.digest(), result.releaseDigest);
    }
  }

  @Test
  public void authenticatedPauseResumeAndHandoffAreDurable() {
    ReleaseIdentity release = ReleaseFixtures.release();
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      Worker worker = environment.newWorker(QueueNames.releaseWorkflow(release));
      worker.registerWorkflowImplementationTypes(ReleaseWorkflowImpl.class);
      environment.start();
      StartedRelease started = startRelease(environment, release);
      ReleaseWorkflow workflow = started.workflow;
      ReleaseStatus initial = workflow.status();

      ReleaseStatus paused =
          workflow.control(control("pause", started.runId, release, 200, "release-manager"));
      assertEquals("PAUSED", paused.phase);
      ReleaseStatus resumed =
          workflow.control(control("resume", started.runId, release, 201, "release-manager"));
      assertEquals("AWAITING_APPROVAL", resumed.phase);
      ReleaseStatus handedOff =
          workflow.control(
              control("handoff-manual", started.runId, release, 202, "release-manager"));
      assertEquals("HANDED_OFF", handedOff.phase);
      assertEquals("HANDED_OFF", workflow.status().phase);
    }
  }

  private static StartedRelease startRelease(
      TestWorkflowEnvironment environment, ReleaseIdentity release) {
    ReleaseWorkflow starter =
        environment
            .getWorkflowClient()
            .newWorkflowStub(
                ReleaseWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setWorkflowId(QueueNames.releaseWorkflowId(release))
                    .setTaskQueue(QueueNames.releaseWorkflow(release))
                    .build());
    WorkflowExecution execution = WorkflowClient.start(starter::release, release);
    ReleaseWorkflow workflow =
        environment
            .getWorkflowClient()
            .newWorkflowStub(
                ReleaseWorkflow.class,
                WorkflowTargetOptions.newBuilder().setWorkflowExecution(execution).build());
    return new StartedRelease(workflow, execution.getRunId());
  }

  private static ControlEvidence control(
      String action, String runId, ReleaseIdentity release, long githubRunId, String actor) {
    return new ControlEvidence(
        action,
        CandidateIdentity.REPOSITORY,
        release.digest(),
        QueueNames.releaseWorkflowId(release),
        runId,
        githubRunId,
        actor,
        release.candidate.tag,
        release.candidate.commitSha,
        "Test control evidence.");
  }

  private static final class StartedRelease {
    final ReleaseWorkflow workflow;
    final String runId;

    private StartedRelease(ReleaseWorkflow workflow, String runId) {
      this.workflow = workflow;
      this.runId = runId;
    }
  }
}
