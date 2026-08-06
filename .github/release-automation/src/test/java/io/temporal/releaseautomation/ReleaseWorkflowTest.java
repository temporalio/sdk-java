package io.temporal.releaseautomation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class ReleaseWorkflowTest {
  @Test
  public void publishesAfterExactApprovalAndMavenArtifactUpdate() {
    ReleaseIdentity release = ReleaseFixtures.release();
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      registerReleaseAndOwnership(environment, release);
      registerPublication(environment, release, successfulActivities());
      environment.start();
      StartedRelease started = startRelease(environment, release);
      environment.sleep(Duration.ofSeconds(1));
      requestApproveAndRecordPayload(started, release);

      ReleaseResult result =
          WorkflowStub.fromTyped(started.workflow).getResult(ReleaseResult.class);
      assertEquals(release.digest(), result.releaseDigest);
      assertEquals("PUBLISHED", started.workflow.status().phase);
    }
  }

  @Test
  public void handoffTransfersTagOwnershipToTheExistingManualWorkflow() {
    ReleaseIdentity release = ReleaseFixtures.release();
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      registerReleaseAndOwnership(environment, release);
      environment.start();
      StartedRelease started = startRelease(environment, release);
      environment.sleep(Duration.ofSeconds(1));

      ReleaseStatus handedOff =
          started.workflow.control(
              control("handoff-manual", started.runId, release, 202, "release-manager"));
      assertEquals("HANDED_OFF", handedOff.phase);
      assertEquals("MANUAL", handedOff.ownership.owner);
      assertEquals(
          "MANUAL",
          OwnershipActivitiesImpl.status(environment.getWorkflowClient(), release.candidate.tag)
              .owner);
    }
  }

  @Test
  public void anExistingManualReleaseOwnsTheTagWithoutAnAutomaticWorkflow() {
    ReleaseIdentity release = ReleaseFixtures.release();
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      registerReleaseAndOwnership(environment, release);
      environment.start();
      OwnershipStatus ownership =
          OwnershipActivitiesImpl.claim(
              environment.getWorkflowClient(),
              OwnershipClaim.manual(
                  release.candidate.tag,
                  release.candidate.commitSha,
                  release.digest(),
                  "release-manager",
                  201,
                  false));
      assertEquals("MANUAL", ownership.owner);
      ReleaseOwnershipWorkflow ownershipWorkflow =
          environment
              .getWorkflowClient()
              .newWorkflowStub(
                  ReleaseOwnershipWorkflow.class,
                  QueueNames.ownershipWorkflowId(release.candidate.tag));
      OwnershipStatus startedMaven =
          ownershipWorkflow.recordManualMaven(
              new ManualMavenAttempt(
                  "STARTED",
                  release.candidate.tag,
                  release.candidate.commitSha,
                  release.digest(),
                  "release-manager",
                  201));
      assertEquals("STARTED", startedMaven.manualMavenState);
      OwnershipStatus completedMaven =
          ownershipWorkflow.recordManualMaven(
              new ManualMavenAttempt(
                  "COMPLETED",
                  release.candidate.tag,
                  release.candidate.commitSha,
                  release.digest(),
                  "release-manager",
                  201));
      assertEquals("COMPLETED", completedMaven.manualMavenState);

      StartedRelease started = startRelease(environment, release);
      environment.sleep(Duration.ofSeconds(1));
      assertEquals("HANDED_OFF", started.workflow.status().phase);
      assertEquals("MANUAL", started.workflow.status().ownership.owner);
    }
  }

  @Test
  public void aCurrentManagerCanRebindAnAmbiguousMavenGeneration() {
    ReleaseIdentity release = ReleaseFixtures.release();
    AtomicInteger repositoryAttempts = new AtomicInteger();
    PublicationActivities activities =
        new SuccessfulActivities() {
          @Override
          public String reconcileMavenRepository(PublicationInput input, boolean allowCreation) {
            int attempt = repositoryAttempts.getAndIncrement();
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
            return "io-temporal-1001";
          }
        };
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      registerReleaseAndOwnership(environment, release);
      registerPublication(environment, release, activities);
      Worker retryWorker = environment.newWorker(QueueNames.publication(release, 1));
      retryWorker.registerActivitiesImplementations(activities);
      environment.start();
      StartedRelease started = startRelease(environment, release);
      environment.sleep(Duration.ofSeconds(1));
      requestApproveAndRecordPayload(started, release);
      environment.sleep(Duration.ofSeconds(1));

      ReleaseStatus blocked = started.workflow.status();
      assertEquals("BLOCKED", blocked.phase);
      assertTrue(blocked.lastError.contains("MavenSubmissionAmbiguous"));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              ReleaseWorkflowImpl.validateInspectedGenerations(
                  blocked.mavenGenerations,
                  mavenRetry(started.runId, release, 400, "first-manager", 1, 0).mavenInspection));
      started.workflow.control(mavenRetry(started.runId, release, 401, "first-manager", 1, 1));
      environment.sleep(Duration.ofSeconds(1));
      assertTrue(started.workflow.status().lastError.contains("InvalidApproval"));
      started.workflow.control(mavenRetry(started.runId, release, 402, "current-manager", 1, 2));

      WorkflowStub.fromTyped(started.workflow).getResult(ReleaseResult.class);
      assertEquals(3, repositoryAttempts.get());
    }
  }

  @Test
  public void handoffAfterAutomaticMavenCompletionPreservesSubmissionState() {
    ReleaseIdentity release = ReleaseFixtures.release();
    PublicationActivities activities =
        new SuccessfulActivities() {
          @Override
          public String reconcileGithubDraft(PublicationInput input) {
            throw ApplicationFailure.newNonRetryableFailure(
                "draft publication is unavailable", "ReleaseIdentityConflict");
          }
        };
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      registerReleaseAndOwnership(environment, release);
      registerPublication(environment, release, activities);
      environment.start();
      StartedRelease started = startRelease(environment, release);
      environment.sleep(Duration.ofSeconds(1));
      requestApproveAndRecordPayload(started, release);
      environment.sleep(Duration.ofSeconds(1));

      ReleaseStatus blocked = started.workflow.status();
      assertEquals("BLOCKED", blocked.phase);
      assertTrue(blocked.mavenGenerations.get(0).submissionStarted);
      assertTrue(blocked.mavenCentralUrl != null && !blocked.mavenCentralUrl.isEmpty());
      ReleaseStatus handedOff =
          started.workflow.control(
              control("handoff-manual", started.runId, release, 500, "release-manager"));
      assertEquals("HANDED_OFF", handedOff.phase);
      assertEquals("GITHUB_DRAFT", handedOff.handedOffFrom);
      assertTrue(handedOff.mavenGenerations.get(0).submissionStarted);
      assertEquals("MANUAL", handedOff.ownership.owner);
    }
  }

  @Test
  public void partialAutomaticMavenCannotBeHandedToTheLegacyManualPublisher() {
    ReleaseIdentity release = ReleaseFixtures.release();
    MavenGenerationState started = new MavenGenerationState(release.digest(), 0);
    started.submissionStarted = true;
    assertThrows(
        IllegalStateException.class,
        () -> ReleaseWorkflowImpl.validateManualHandoff(List.of(started), null, false));
    assertThrows(
        IllegalStateException.class,
        () -> ReleaseWorkflowImpl.validateManualHandoff(List.of(started), null, true));
    ReleaseWorkflowImpl.validateManualHandoff(
        List.of(started), "https://central.example/artifact", false);
  }

  private static void registerReleaseAndOwnership(
      TestWorkflowEnvironment environment, ReleaseIdentity release) {
    Worker releaseWorker = environment.newWorker(QueueNames.releaseWorkflow(release));
    releaseWorker.registerWorkflowImplementationTypes(ReleaseWorkflowImpl.class);
    Worker ownershipWorker = environment.newWorker(QueueNames.ownership(release.candidate.tag));
    ownershipWorker.registerWorkflowImplementationTypes(ReleaseOwnershipWorkflowImpl.class);
    ownershipWorker.registerActivitiesImplementations(
        new OwnershipActivitiesImpl(environment.getWorkflowClient()));
  }

  private static void registerPublication(
      TestWorkflowEnvironment environment,
      ReleaseIdentity release,
      PublicationActivities activities) {
    Worker worker = environment.newWorker(QueueNames.publication(release));
    worker.registerActivitiesImplementations(activities);
  }

  private static PublicationActivities successfulActivities() {
    return new SuccessfulActivities();
  }

  private static class SuccessfulActivities implements PublicationActivities {
    @Override
    public void preflight(PublicationInput input) {}

    @Override
    public String reconcileMavenRepository(PublicationInput input, boolean allowCreation) {
      return "io-temporal-1000";
    }

    @Override
    public String reconcileMavenPortal(PublicationInput input) {
      return "12345678-1234-1234-1234-123456789abc";
    }

    @Override
    public MavenReceipt publishMaven(PublicationInput input) {
      return new MavenReceipt(
          "https://central.example/artifact",
          "io-temporal-1000",
          "12345678-1234-1234-1234-123456789abc");
    }

    @Override
    public String reconcileGithubDraft(PublicationInput input) {
      return "https://github.example/draft";
    }

    @Override
    public ReleaseResult publishGithubRelease(PublicationInput input, String mavenCentralUrl) {
      return new ReleaseResult(
          input.release.digest(), "https://github.example/release", mavenCentralUrl);
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

  private static void requestApproveAndRecordPayload(
      StartedRelease started, ReleaseIdentity release) {
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
            "approval-bot",
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
    started.workflow.recordMavenPayload(ReleaseFixtures.mavenArtifact(release));
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
      String runId,
      ReleaseIdentity release,
      long githubRunId,
      String actor,
      int generation,
      int inspectedGenerations) {
    ControlEvidence evidence = new ControlEvidence();
    evidence.action = "retry-maven-submission";
    evidence.releaseDigest = release.digest();
    evidence.workflowId = QueueNames.releaseWorkflowId(release);
    evidence.runId = runId;
    evidence.githubRunId = githubRunId;
    evidence.githubActor = actor;
    evidence.tag = release.candidate.tag;
    evidence.commitSha = release.candidate.commitSha;
    evidence.reason = "Test Maven retry authorization.";
    evidence.mavenSubmissionGeneration = generation;
    evidence.mavenInspection = new MavenInspection();
    evidence.mavenInspection.centralMissing =
        ReleasePolicy.mavenArtifacts(release.candidate.mavenPolicy).size();
    for (int inspectedGeneration = 0;
        inspectedGeneration < inspectedGenerations;
        inspectedGeneration++) {
      MavenGenerationInspection inspected = new MavenGenerationInspection();
      inspected.generation = inspectedGeneration;
      inspected.description = "sdk-java:" + release.digest() + ":" + inspectedGeneration;
      inspected.repositoryId = "";
      inspected.repositoryState = "absent";
      inspected.portalDeploymentId = "";
      inspected.portalDeploymentState = "";
      evidence.mavenInspection.generations.add(inspected);
    }
    evidence.authorizationSha256 =
        Digests.sha256(evidence.mavenInspection.canonicalForm(release.digest()));
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
