package io.temporal.releaseautomation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.client.WorkflowTargetOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class ReleaseWorkflowTest {
  @Test
  public void waitsIndefinitelyForExactUpdateThenPublishes() {
    ReleaseIdentity release = ReleaseFixtures.release();
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      Worker workflowWorker = environment.newWorker(QueueNames.releaseWorkflow(release));
      workflowWorker.registerWorkflowImplementationTypes(ReleaseWorkflowImpl.class);
      PublicationActivities activities =
          new PublicationActivities() {
            @Override
            public void preflight(PublicationInput input) {}

            @Override
            public MavenReceipt reconcileMaven(PublicationInput input) {
              return new MavenReceipt("https://central.example/artifact", "io-temporal-1000");
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
          };
      Worker publicationWorker = environment.newWorker(QueueNames.publication(release));
      publicationWorker.registerActivitiesImplementations(activities);
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

  @Test
  public void terminalPublicationFailureRemainsOpenAndCanBeHandedOff() {
    ReleaseIdentity release = ReleaseFixtures.release();
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      Worker workflowWorker = environment.newWorker(QueueNames.releaseWorkflow(release));
      workflowWorker.registerWorkflowImplementationTypes(ReleaseWorkflowImpl.class);
      Worker publicationWorker = environment.newWorker(QueueNames.publication(release));
      publicationWorker.registerActivitiesImplementations(
          new PublicationActivities() {
            @Override
            public void preflight(PublicationInput input) {
              throw ApplicationFailure.newNonRetryableFailure(
                  "immutable conflict", "ReleaseIdentityConflict");
            }

            @Override
            public MavenReceipt reconcileMaven(PublicationInput input) {
              throw new AssertionError("Maven must not run after failed preflight.");
            }

            @Override
            public String reconcileGithubDraft(PublicationInput input) {
              throw new AssertionError("GitHub must not run after failed preflight.");
            }

            @Override
            public ReleaseResult publishGithubRelease(
                PublicationInput input, String mavenCentralUrl) {
              throw new AssertionError("GitHub must not run after failed preflight.");
            }
          });
      environment.start();
      StartedRelease started = startRelease(environment, release);
      requestAndApprove(started, release);
      environment.sleep(Duration.ofSeconds(1));

      ReleaseStatus blocked = started.workflow.status();
      assertEquals("BLOCKED", blocked.phase);
      assertEquals("PREFLIGHT", blocked.pausedFrom);
      assertTrue(blocked.lastError != null && !blocked.lastError.isEmpty());

      ReleaseStatus handedOff =
          started.workflow.control(
              control("handoff-manual", started.runId, release, 301, "release-manager"));
      assertEquals("HANDED_OFF", handedOff.phase);
    }
  }

  @Test
  public void staleMavenAuthorizationCanBeReplacedWithoutAnotherGeneration() {
    ReleaseIdentity release = ReleaseFixtures.release();
    AtomicInteger mavenAttempts = new AtomicInteger();
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      Worker workflowWorker = environment.newWorker(QueueNames.releaseWorkflow(release));
      workflowWorker.registerWorkflowImplementationTypes(ReleaseWorkflowImpl.class);
      PublicationActivities activities =
          new PublicationActivities() {
            @Override
            public void preflight(PublicationInput input) {}

            @Override
            public MavenReceipt reconcileMaven(PublicationInput input) {
              int attempt = mavenAttempts.getAndIncrement();
              if (attempt == 0) {
                assertEquals(0, input.mavenSubmissionGeneration);
                throw ApplicationFailure.newNonRetryableFailure(
                    "repository creation was ambiguous", "MavenSubmissionAmbiguous");
              }
              assertEquals(1, input.mavenSubmissionGeneration);
              if (attempt == 1) {
                assertEquals("first-manager", input.mavenRetryAuthorization.githubActor);
                throw ApplicationFailure.newNonRetryableFailure(
                    "the authorizer is no longer active", "InvalidApproval");
              }
              assertEquals("current-manager", input.mavenRetryAuthorization.githubActor);
              return new MavenReceipt("https://central.example/artifact", "io-temporal-1001");
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
          };
      Worker publicationWorker = environment.newWorker(QueueNames.publication(release));
      publicationWorker.registerActivitiesImplementations(activities);
      Worker retryPublicationWorker = environment.newWorker(QueueNames.publication(release, 1));
      retryPublicationWorker.registerActivitiesImplementations(activities);
      environment.start();
      StartedRelease started = startRelease(environment, release);
      requestAndApprove(started, release);
      environment.sleep(Duration.ofSeconds(1));

      ReleaseStatus blocked = started.workflow.status();
      assertEquals("BLOCKED", blocked.phase);
      assertEquals("MAVEN", blocked.pausedFrom);
      assertTrue(blocked.lastError.contains("MavenSubmissionAmbiguous"));
      ReleaseStatus retried =
          started.workflow.control(mavenRetry(started.runId, release, 401, "first-manager"));
      assertEquals(1, retried.mavenSubmissionGeneration);
      environment.sleep(Duration.ofSeconds(1));
      ReleaseStatus stale = started.workflow.status();
      assertEquals("BLOCKED", stale.phase);
      assertTrue(stale.lastError.contains("InvalidApproval"));
      ReleaseStatus rebound =
          started.workflow.control(mavenRetry(started.runId, release, 402, "current-manager"));
      assertEquals(1, rebound.mavenSubmissionGeneration);
      WorkflowStub.fromTyped(started.workflow).getResult(ReleaseResult.class);
      assertEquals(3, mavenAttempts.get());
    }
  }

  private static void requestAndApprove(StartedRelease started, ReleaseIdentity release) {
    String workflowId = QueueNames.releaseWorkflowId(release);
    started.workflow.requestApproval(
        new ApprovalRequest(
            release.digest(),
            workflowId,
            started.runId,
            300,
            43,
            "ISSUE_node_43",
            "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
            release.candidate.trustedAutomationCommit));
    started.workflow.approve(
        new ApprovalEvidence(
            release.digest(),
            workflowId,
            started.runId,
            300,
            "release-manager",
            43,
            "ISSUE_node_43",
            "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
            release.candidate.trustedAutomationCommit));
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
        release.digest(),
        QueueNames.releaseWorkflowId(release),
        runId,
        githubRunId,
        actor,
        release.candidate.tag,
        release.candidate.commitSha,
        "Test control evidence.");
  }

  private static ControlEvidence mavenRetry(
      String runId, ReleaseIdentity release, long githubRunId, String actor) {
    ControlEvidence evidence = new ControlEvidence();
    evidence.action = "retry-maven-submission";
    evidence.releaseDigest = release.digest();
    evidence.workflowId = QueueNames.releaseWorkflowId(release);
    evidence.runId = runId;
    evidence.githubRunId = githubRunId;
    evidence.githubActor = actor;
    evidence.tag = release.candidate.tag;
    evidence.commitSha = release.candidate.commitSha;
    evidence.reason = "Test retry authorization.";
    evidence.mavenSubmissionGeneration = 1;
    evidence.authorizationSha256 =
        "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd";
    evidence.validate();
    return evidence;
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
