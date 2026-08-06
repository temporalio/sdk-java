package io.temporal.releaseautomation;

import io.temporal.activity.ActivityCancellationType;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.CancellationScope;
import io.temporal.workflow.UpdateValidatorMethod;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ReleaseWorkflowImpl implements ReleaseWorkflow {
  static final String STATUS_MEMO_KEY = "ReleaseStatus";
  static final String IDENTITY_MEMO_KEY = "ReleaseIdentity";
  private ReleaseIdentity identity;
  private ApprovalRequest approvalRequest;
  private ApprovalEvidence approval;
  private ControlEvidence control;
  private String phase = "INITIALIZING";
  private String pausedFrom;
  private String handedOffFrom;
  private String lastCompletedStage;
  private String lastError;
  private long blockedAtMillis;
  private String mavenCentralUrl;
  private String sonatypeRepositoryId;
  private String portalDeploymentId;
  private String githubDraftUrl;
  private String githubReleaseUrl;
  private int mavenSubmissionGeneration;
  private ControlEvidence mavenRetryAuthorization;
  private GithubArtifactReceipt mavenPayload;
  private final List<MavenGenerationState> mavenGenerations = new ArrayList<>();
  private OwnershipStatus ownership;
  private int stageAttempt;
  private long stageStartedAtMillis;
  private long nextRetryAtMillis;
  private boolean pauseRequested;
  private boolean handoffRequested;
  private CancellationScope activeActivity;

  @Override
  public ReleaseResult release(ReleaseIdentity releaseIdentity) {
    releaseIdentity.validate();
    identity = releaseIdentity;
    ownership = ownershipActivities().claimTemporal(identity);
    if ("MANUAL".equals(ownership.owner)) {
      handedOffFrom = "INITIALIZING";
      enterHandedOff();
      return awaitHandoff();
    }
    awaitApproval();
    if (handoffRequested) {
      enterHandedOff();
      return awaitHandoff();
    }

    awaitMavenPayload();
    if (handoffRequested) {
      return awaitHandoff();
    }
    runStage("PREFLIGHT", () -> publicationActivities().preflight(publicationInput()));
    if (handoffRequested) {
      return awaitHandoff();
    }
    runMaven();
    if (handoffRequested) {
      return awaitHandoff();
    }
    runStage(
        "GITHUB_DRAFT",
        () -> githubDraftUrl = publicationActivities().reconcileGithubDraft(publicationInput()));
    final ReleaseResult[] result = new ReleaseResult[1];
    runStage(
        "PUBLISH_GITHUB",
        () ->
            result[0] =
                publicationActivities().publishGithubRelease(publicationInput(), mavenCentralUrl));
    if (handoffRequested) {
      enterHandedOff();
      return awaitHandoff();
    }
    phase = "PUBLISHED";
    githubReleaseUrl = result[0].githubReleaseUrl;
    upsertStatus();
    return result[0];
  }

  @Override
  public ReleaseStatus requestApproval(ApprovalRequest request) {
    validateApprovalRequest(request);
    approvalRequest = request;
    upsertStatus();
    return status();
  }

  @UpdateValidatorMethod(updateName = "requestApproval")
  public void validateApprovalRequest(ApprovalRequest request) {
    if (identity == null || !"AWAITING_APPROVAL".equals(phase) || approvalRequest != null) {
      throw new IllegalStateException("The release cannot accept an approval request.");
    }
    request.validate();
    validateExecutionIdentity(request.releaseDigest, request.workflowId, request.runId);
    if (!identity.candidate.trustedAutomationCommit.equals(request.trustedWorkerCommit)) {
      throw new IllegalArgumentException("Approval request uses another trusted Worker commit.");
    }
  }

  @Override
  public ReleaseStatus approve(ApprovalEvidence evidence) {
    validateApproval(evidence);
    approval = evidence;
    upsertStatus();
    return status();
  }

  @UpdateValidatorMethod(updateName = "approve")
  public void validateApproval(ApprovalEvidence evidence) {
    if (identity == null || !"AWAITING_APPROVAL".equals(phase) || approvalRequest == null) {
      throw new IllegalStateException("The release is not awaiting approval.");
    }
    evidence.validate();
    validateExecutionIdentity(evidence.releaseDigest, evidence.workflowId, evidence.runId);
    if (!approvalRequest.matches(evidence)) {
      throw new IllegalArgumentException("Approval does not match the recorded approval request.");
    }
  }

  @Override
  public ReleaseStatus recordMavenPayload(GithubArtifactReceipt artifact) {
    validateMavenPayload(artifact);
    if (mavenPayload == null) {
      mavenPayload = artifact;
    }
    upsertStatus();
    return status();
  }

  @UpdateValidatorMethod(updateName = "recordMavenPayload")
  public void validateMavenPayload(GithubArtifactReceipt artifact) {
    if (identity == null
        || approval == null
        || "PUBLISHED".equals(phase)
        || "HANDED_OFF".equals(phase)) {
      throw new IllegalStateException("The release cannot accept a Maven payload.");
    }
    artifact.validate();
    if (!ReleasePolicy.githubMavenArtifactName(identity).equals(artifact.artifactName)
        || artifact.files.size() != 1
        || !"maven-payload.tar".equals(artifact.files.get(0).name)) {
      throw new IllegalArgumentException("The Maven GitHub artifact identity is invalid.");
    }
    if (mavenPayload != null && !mavenPayload.canonicalForm().equals(artifact.canonicalForm())) {
      throw new IllegalStateException("The release already recorded another Maven payload.");
    }
  }

  @Override
  public ReleaseStatus control(ControlEvidence evidence) {
    validateControl(evidence);
    control = evidence;
    control.recordedAtMillis = Workflow.currentTimeMillis();
    if ("pause".equals(evidence.action)) {
      pauseRequested = true;
      beginQuiescing();
      cancelActiveActivity();
      Workflow.await(() -> "PAUSED".equals(phase) || "HANDED_OFF".equals(phase));
    } else if ("resume".equals(evidence.action)) {
      pauseRequested = false;
      phase = pausedFrom;
      pausedFrom = null;
      lastError = null;
      blockedAtMillis = 0;
    } else if ("retry-maven-submission".equals(evidence.action)) {
      boolean nextGeneration = evidence.mavenSubmissionGeneration > mavenSubmissionGeneration;
      adoptInspectedGeneration(evidence.mavenInspection);
      mavenSubmissionGeneration = evidence.mavenSubmissionGeneration;
      mavenRetryAuthorization = evidence;
      phase = nextGeneration ? "MAVEN_REPOSITORY" : pausedFrom;
      pausedFrom = null;
      lastError = null;
      blockedAtMillis = 0;
    } else {
      handoffRequested = true;
      pauseRequested = false;
      beginQuiescing();
      cancelActiveActivity();
      if (activeActivity == null) {
        enterHandedOff();
      }
      Workflow.await(() -> "HANDED_OFF".equals(phase));
      ownership = ownershipActivities().handoffManual(identity, evidence);
      if (!"MANUAL".equals(ownership.owner)) {
        throw new IllegalStateException("Temporal ownership handoff did not complete.");
      }
    }
    upsertStatus();
    return status();
  }

  @UpdateValidatorMethod(updateName = "control")
  public void validateControl(ControlEvidence evidence) {
    if (identity == null || "PUBLISHED".equals(phase) || "HANDED_OFF".equals(phase)) {
      throw new IllegalStateException("The release is not controllable.");
    }
    evidence.validate();
    validateExecutionIdentity(evidence.releaseDigest, evidence.workflowId, evidence.runId);
    if (!identity.candidate.tag.equals(evidence.tag)
        || !identity.candidate.commitSha.equals(evidence.commitSha)) {
      throw new IllegalArgumentException("Control evidence does not match the exact tag and SHA.");
    }
    if ("resume".equals(evidence.action) && !("PAUSED".equals(phase) || "BLOCKED".equals(phase))) {
      throw new IllegalStateException("Only a paused or blocked release can resume.");
    }
    if ("retry-maven-submission".equals(evidence.action)) {
      if (evidence.mavenInspection.centralPresent + evidence.mavenInspection.centralMissing
          != ReleasePolicy.mavenArtifacts(identity.candidate.mavenPolicy).size()) {
        throw new IllegalArgumentException("Maven inspection does not match the release policy.");
      }
      validateInspectedGenerations(mavenGenerations, evidence.mavenInspection);
      boolean nextGeneration =
          "BLOCKED".equals(phase)
              && pausedFrom != null
              && pausedFrom.startsWith("MAVEN_")
              && lastError != null
              && (lastError.contains("MavenSubmissionAmbiguous")
                  || lastError.contains("MavenDeploymentFailed"))
              && evidence.mavenSubmissionGeneration == mavenSubmissionGeneration + 1;
      boolean replaceAuthorization =
          "BLOCKED".equals(phase)
              && pausedFrom != null
              && pausedFrom.startsWith("MAVEN_")
              && lastError != null
              && lastError.contains("InvalidApproval")
              && mavenSubmissionGeneration > 0
              && evidence.mavenSubmissionGeneration == mavenSubmissionGeneration;
      if (!(nextGeneration || replaceAuthorization)) {
        throw new IllegalStateException(
            "Maven authorization must advance an ambiguous attempt or replace stale evidence.");
      }
      if (nextGeneration && evidence.mavenInspection.centralPresent != 0) {
        throw new IllegalStateException(
            "A new Maven generation requires Central to be completely absent.");
      }
      if (nextGeneration) {
        for (MavenGenerationInspection inspected : evidence.mavenInspection.generations) {
          boolean failedPortal = "FAILED".equals(inspected.portalDeploymentState);
          if (!("absent".equals(inspected.repositoryState)
                  || (failedPortal && "released".equals(inspected.repositoryState)))
              || !(inspected.portalDeploymentState.isEmpty() || failedPortal)) {
            throw new IllegalStateException(
                "A new Maven generation requires every earlier attempt to be inactive.");
          }
        }
      }
    } else if ("handoff-manual".equals(evidence.action)) {
      validateManualHandoff(mavenGenerations, mavenCentralUrl, evidence.manualMavenRequested);
    }
  }

  static void validateManualHandoff(
      List<MavenGenerationState> generations, String centralUrl, boolean manualMavenRequested) {
    boolean mavenStarted = false;
    for (MavenGenerationState generation : generations) {
      mavenStarted |= generation.submissionStarted;
    }
    boolean mavenCompleted = centralUrl != null && !centralUrl.isEmpty();
    if (manualMavenRequested && mavenStarted) {
      throw new IllegalStateException(
          "Manual Maven publication cannot take over after automatic Maven submission started.");
    }
    if (!manualMavenRequested && mavenStarted && !mavenCompleted) {
      throw new IllegalStateException(
          "Manual non-Maven takeover requires automatic Maven publication to be complete.");
    }
  }

  @Override
  public ReleaseStatus status() {
    ReleaseStatus status = new ReleaseStatus();
    status.phase = phase;
    status.identity = identity;
    status.approvalRequest = approvalRequest;
    status.approval = approval;
    status.control = control;
    status.pausedFrom = pausedFrom;
    status.handedOffFrom = handedOffFrom;
    status.lastCompletedStage = lastCompletedStage;
    status.lastError = lastError;
    status.blockedAtMillis = blockedAtMillis;
    status.mavenCentralUrl = mavenCentralUrl;
    status.sonatypeRepositoryId = sonatypeRepositoryId;
    status.portalDeploymentId = portalDeploymentId;
    status.githubDraftUrl = githubDraftUrl;
    status.githubReleaseUrl = githubReleaseUrl;
    status.mavenSubmissionGeneration = mavenSubmissionGeneration;
    status.mavenRetryAuthorization = mavenRetryAuthorization;
    status.mavenPayload = mavenPayload;
    status.mavenGenerations = new ArrayList<>(mavenGenerations);
    status.ownership = ownership;
    status.stageAttempt = stageAttempt;
    status.stageStartedAtMillis = stageStartedAtMillis;
    status.nextRetryAtMillis = nextRetryAtMillis;
    return status;
  }

  private void awaitApproval() {
    phase = "AWAITING_APPROVAL";
    upsertStatus();
    while (approval == null && !handoffRequested) {
      handlePause("AWAITING_APPROVAL");
      Workflow.await(() -> approval != null || pauseRequested || handoffRequested);
    }
  }

  private void awaitMavenPayload() {
    while (mavenPayload == null && !handoffRequested) {
      handlePause("AWAITING_MAVEN_PAYLOAD");
      phase = "AWAITING_MAVEN_PAYLOAD";
      upsertStatus();
      Workflow.await(() -> mavenPayload != null || pauseRequested || handoffRequested);
    }
  }

  private void runMaven() {
    while (!handoffRequested) {
      runStage(
          "MAVEN_REPOSITORY",
          () -> {
            MavenGenerationState current = currentMavenGeneration();
            boolean allowCreation = !current.submissionStarted;
            if (allowCreation) {
              current.submissionStarted = true;
              upsertStatus();
            }
            current.sonatypeRepositoryId =
                publicationActivities().reconcileMavenRepository(publicationInput(), allowCreation);
            sonatypeRepositoryId = current.sonatypeRepositoryId;
            upsertStatus();
          });
      if (handoffRequested) {
        return;
      }
      if (currentMavenGeneration().sonatypeRepositoryId == null) {
        return;
      }
      int generation = mavenSubmissionGeneration;
      runStage(
          "MAVEN_PORTAL",
          () -> {
            MavenGenerationState current = currentMavenGeneration();
            current.portalDeploymentId =
                publicationActivities().reconcileMavenPortal(publicationInput());
            portalDeploymentId = current.portalDeploymentId;
            upsertStatus();
          });
      if (generation != mavenSubmissionGeneration || handoffRequested) {
        continue;
      }
      if (currentMavenGeneration().portalDeploymentId == null) {
        return;
      }
      runStage(
          "MAVEN_PUBLISH",
          () -> {
            MavenReceipt receipt = publicationActivities().publishMaven(publicationInput());
            mavenCentralUrl = receipt.mavenCentralUrl;
            sonatypeRepositoryId = receipt.sonatypeRepositoryId;
            portalDeploymentId = receipt.portalDeploymentId;
          });
      if (generation == mavenSubmissionGeneration) {
        return;
      }
    }
  }

  private void runStage(String stage, Runnable action) {
    int retry = 0;
    while (!handoffRequested) {
      handlePause(stage);
      if (handoffRequested) {
        return;
      }
      phase = stage;
      stageAttempt++;
      stageStartedAtMillis = Workflow.currentTimeMillis();
      nextRetryAtMillis = 0;
      upsertStatus();
      activeActivity = Workflow.newCancellationScope(action);
      try {
        activeActivity.run();
        activeActivity = null;
        lastCompletedStage = stage;
        lastError = null;
        blockedAtMillis = 0;
        nextRetryAtMillis = 0;
        upsertStatus();
        return;
      } catch (RuntimeException e) {
        activeActivity = null;
        if (handoffRequested) {
          enterHandedOff();
          return;
        }
        if (!pauseRequested && isNonRetryable(e)) {
          pausedFrom = stage;
          phase = "BLOCKED";
          lastError = safeFailure(e);
          blockedAtMillis = Workflow.currentTimeMillis();
          upsertStatus();
          Workflow.await(() -> !"BLOCKED".equals(phase) || handoffRequested);
          if (!stage.equals(phase)) {
            return;
          }
        } else if (!pauseRequested) {
          lastError = safeFailure(e);
          long delayMinutes = Math.min(15, 2L << Math.min(retry, 3));
          retry++;
          nextRetryAtMillis =
              Workflow.currentTimeMillis() + Duration.ofMinutes(delayMinutes).toMillis();
          upsertStatus();
          Workflow.await(
              Duration.ofMinutes(delayMinutes), () -> pauseRequested || handoffRequested);
        }
      }
    }
  }

  private void handlePause(String resumeStage) {
    if (!pauseRequested) {
      return;
    }
    pausedFrom = resumeStage;
    phase = "PAUSED";
    upsertStatus();
    Workflow.await(() -> !pauseRequested || handoffRequested);
    if (!handoffRequested) {
      phase = resumeStage;
      pausedFrom = null;
      upsertStatus();
    }
  }

  private void enterHandedOff() {
    if (handedOffFrom == null) {
      handedOffFrom = pausedFrom == null ? phase : pausedFrom;
    }
    phase = "HANDED_OFF";
    pausedFrom = null;
    upsertStatus();
  }

  private void beginQuiescing() {
    if (activeActivity != null) {
      if (pausedFrom == null) {
        pausedFrom = phase;
      }
      phase = "QUIESCING";
      upsertStatus();
    } else if (handoffRequested) {
      enterHandedOff();
    } else if (!"PAUSED".equals(phase)) {
      pausedFrom = phase;
      phase = "PAUSED";
      upsertStatus();
    }
  }

  private static String safeFailure(RuntimeException failure) {
    Throwable current = failure;
    while (current != null) {
      if (current instanceof ApplicationFailure) {
        ApplicationFailure applicationFailure = (ApplicationFailure) current;
        return applicationFailure.getType() + ": " + value(applicationFailure);
      }
      current = current.getCause();
    }
    return failure.getClass().getSimpleName() + ": " + value(failure);
  }

  private static String value(Throwable failure) {
    String message = failure.getMessage();
    if (message == null || message.isEmpty()) {
      return failure.getClass().getSimpleName();
    }
    return message;
  }

  private static boolean isNonRetryable(Throwable failure) {
    Throwable current = failure;
    while (current != null) {
      if (current instanceof ApplicationFailure) {
        return ((ApplicationFailure) current).isNonRetryable();
      }
      current = current.getCause();
    }
    return false;
  }

  private void cancelActiveActivity() {
    if (activeActivity != null) {
      activeActivity.cancel("Authenticated release control requested cancellation.");
    }
  }

  private PublicationInput publicationInput() {
    PublicationInput input =
        new PublicationInput(
            identity,
            approvalRequest,
            approval,
            Workflow.getInfo().getWorkflowId(),
            Workflow.getInfo().getRunId());
    input.mavenSubmissionGeneration = mavenSubmissionGeneration;
    input.mavenRetryAuthorization = mavenRetryAuthorization;
    input.mavenPayload = mavenPayload;
    input.mavenGenerations = new ArrayList<>(mavenGenerations);
    return input;
  }

  private MavenGenerationState currentMavenGeneration() {
    for (MavenGenerationState generation : mavenGenerations) {
      if (generation.generation == mavenSubmissionGeneration) {
        generation.validate(identity.digest());
        return generation;
      }
    }
    MavenGenerationState generation =
        new MavenGenerationState(identity.digest(), mavenSubmissionGeneration);
    mavenGenerations.add(generation);
    upsertStatus();
    return generation;
  }

  private void adoptInspectedGeneration(MavenInspection inspection) {
    for (MavenGenerationInspection inspected : inspection.generations) {
      for (MavenGenerationState generation : mavenGenerations) {
        if (generation.generation == inspected.generation) {
          if (generation.sonatypeRepositoryId == null
              || generation.sonatypeRepositoryId.isEmpty()) {
            generation.sonatypeRepositoryId = inspected.repositoryId;
          } else if (inspected.repositoryId != null
              && !inspected.repositoryId.isEmpty()
              && !generation.sonatypeRepositoryId.equals(inspected.repositoryId)) {
            throw new IllegalArgumentException("Inspected Sonatype repository ID differs.");
          }
          if (generation.portalDeploymentId == null || generation.portalDeploymentId.isEmpty()) {
            generation.portalDeploymentId = inspected.portalDeploymentId;
          } else if (inspected.portalDeploymentId != null
              && !inspected.portalDeploymentId.isEmpty()
              && !generation.portalDeploymentId.equals(inspected.portalDeploymentId)) {
            throw new IllegalArgumentException("Inspected Portal deployment ID differs.");
          }
          generation.validate(identity.digest());
        }
      }
    }
  }

  static void validateInspectedGenerations(
      List<MavenGenerationState> durableGenerations, MavenInspection inspection) {
    if (inspection.generations.size() != durableGenerations.size()) {
      throw new IllegalArgumentException(
          "The Maven inspection does not cover every durable generation.");
    }
    for (MavenGenerationState generation : durableGenerations) {
      boolean found = false;
      for (MavenGenerationInspection inspected : inspection.generations) {
        if (generation.generation == inspected.generation) {
          found = true;
          break;
        }
      }
      if (!found) {
        throw new IllegalArgumentException(
            "The Maven inspection does not cover every durable generation.");
      }
    }
  }

  private PublicationActivities publicationActivities() {
    return Workflow.newActivityStub(
        PublicationActivities.class,
        ActivityOptions.newBuilder()
            .setTaskQueue(QueueNames.publication(identity, mavenSubmissionGeneration))
            .setStartToCloseTimeout(Duration.ofMinutes(90))
            .setHeartbeatTimeout(Duration.ofMinutes(1))
            .setCancellationType(ActivityCancellationType.WAIT_CANCELLATION_COMPLETED)
            .setRetryOptions(
                RetryOptions.newBuilder()
                    .setInitialInterval(Duration.ofMinutes(2))
                    .setMaximumInterval(Duration.ofMinutes(15))
                    .setMaximumAttempts(1)
                    .setDoNotRetry("ReleaseIdentityConflict", "InvalidApproval")
                    .build())
            .build());
  }

  private OwnershipActivities ownershipActivities() {
    return Workflow.newActivityStub(
        OwnershipActivities.class,
        ActivityOptions.newBuilder()
            .setTaskQueue(QueueNames.ownership(identity.candidate.tag))
            .setStartToCloseTimeout(Duration.ofMinutes(2))
            .setRetryOptions(
                RetryOptions.newBuilder()
                    .setInitialInterval(Duration.ofSeconds(10))
                    .setMaximumInterval(Duration.ofMinutes(2))
                    .build())
            .build());
  }

  private ReleaseResult awaitHandoff() {
    Workflow.await(() -> false);
    throw new IllegalStateException("A handed-off release cannot resume automatically.");
  }

  private void validateExecutionIdentity(String releaseDigest, String workflowId, String runId) {
    if (!identity.digest().equals(releaseDigest)
        || !Workflow.getInfo().getWorkflowId().equals(workflowId)
        || !Workflow.getInfo().getRunId().equals(runId)) {
      throw new IllegalArgumentException("Evidence does not identify this exact release run.");
    }
  }

  private void upsertStatus() {
    Workflow.upsertMemo(Collections.singletonMap(STATUS_MEMO_KEY, status()));
  }
}
