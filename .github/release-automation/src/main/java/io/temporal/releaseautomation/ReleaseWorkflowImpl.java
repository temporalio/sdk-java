package io.temporal.releaseautomation;

import io.temporal.activity.ActivityCancellationType;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.CancellationScope;
import io.temporal.workflow.UpdateValidatorMethod;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.Collections;

public final class ReleaseWorkflowImpl implements ReleaseWorkflow {
  static final String STATUS_MEMO_KEY = "ReleaseStatus";
  static final String IDENTITY_MEMO_KEY = "ReleaseIdentity";
  private ReleaseIdentity identity;
  private ApprovalRequest approvalRequest;
  private ApprovalEvidence approval;
  private ControlEvidence control;
  private String phase = "INITIALIZING";
  private String pausedFrom;
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
    awaitApproval();
    if (handoffRequested) {
      enterHandedOff();
      return awaitHandoff();
    }

    runStage("PREFLIGHT", () -> publicationActivities().preflight(publicationInput()));
    runStage(
        "MAVEN",
        () -> {
          MavenReceipt receipt = publicationActivities().reconcileMaven(publicationInput());
          mavenCentralUrl = receipt.mavenCentralUrl;
          sonatypeRepositoryId = receipt.sonatypeRepositoryId;
          portalDeploymentId = receipt.portalDeploymentId;
        });
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
      mavenSubmissionGeneration = evidence.mavenSubmissionGeneration;
      mavenRetryAuthorization = evidence;
      phase = pausedFrom;
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
      boolean nextGeneration =
          "BLOCKED".equals(phase)
              && "MAVEN".equals(pausedFrom)
              && lastError != null
              && (lastError.contains("MavenSubmissionAmbiguous")
                  || lastError.contains("MavenDeploymentFailed"))
              && evidence.mavenSubmissionGeneration == mavenSubmissionGeneration + 1;
      boolean replaceAuthorization =
          "BLOCKED".equals(phase)
              && "MAVEN".equals(pausedFrom)
              && lastError != null
              && lastError.contains("InvalidApproval")
              && mavenSubmissionGeneration > 0
              && evidence.mavenSubmissionGeneration == mavenSubmissionGeneration;
      if (!(nextGeneration || replaceAuthorization)) {
        throw new IllegalStateException(
            "Maven authorization must advance an ambiguous attempt or replace stale evidence.");
      }
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
            identity, approval, Workflow.getInfo().getWorkflowId(), Workflow.getInfo().getRunId());
    input.mavenSubmissionGeneration = mavenSubmissionGeneration;
    input.mavenRetryAuthorization = mavenRetryAuthorization;
    return input;
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
