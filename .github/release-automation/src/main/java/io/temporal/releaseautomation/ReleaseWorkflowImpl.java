package io.temporal.releaseautomation;

import io.temporal.activity.ActivityCancellationType;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.CancellationScope;
import io.temporal.workflow.UpdateValidatorMethod;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.Collections;

public final class ReleaseWorkflowImpl implements ReleaseWorkflow {
  static final String STATUS_MEMO_KEY = "ReleaseStatus";
  private ReleaseIdentity identity;
  private ApprovalRequest approvalRequest;
  private ApprovalEvidence approval;
  private ControlEvidence control;
  private String phase = "INITIALIZING";
  private String pausedFrom;
  private String lastCompletedStage;
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
      Workflow.await(() -> false);
      return null;
    }

    PublicationActivities activities =
        Workflow.newActivityStub(
            PublicationActivities.class,
            ActivityOptions.newBuilder()
                .setTaskQueue(QueueNames.publication(identity))
                .setStartToCloseTimeout(Duration.ofMinutes(90))
                .setHeartbeatTimeout(Duration.ofMinutes(1))
                .setCancellationType(ActivityCancellationType.WAIT_CANCELLATION_COMPLETED)
                .setRetryOptions(
                    RetryOptions.newBuilder()
                        .setInitialInterval(Duration.ofMinutes(2))
                        .setMaximumInterval(Duration.ofMinutes(15))
                        .setDoNotRetry("ReleaseIdentityConflict", "InvalidApproval")
                        .build())
                .build());
    PublicationInput input =
        new PublicationInput(
            identity, approval, Workflow.getInfo().getWorkflowId(), Workflow.getInfo().getRunId());
    runStage("PREFLIGHT", () -> activities.preflight(input));
    final String[] mavenCentralUrl = new String[1];
    runStage("MAVEN", () -> mavenCentralUrl[0] = activities.reconcileMaven(input));
    runStage("GITHUB_DRAFT", () -> activities.reconcileGithubDraft(input));
    final ReleaseResult[] result = new ReleaseResult[1];
    runStage(
        "PUBLISH_GITHUB",
        () -> result[0] = activities.publishGithubRelease(input, mavenCentralUrl[0]));
    if (handoffRequested) {
      enterHandedOff();
      Workflow.await(() -> false);
      return null;
    }
    phase = "PUBLISHED";
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
    validateExecutionIdentity(
        request.repository, request.releaseDigest, request.workflowId, request.runId);
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
    validateExecutionIdentity(
        evidence.repository, evidence.releaseDigest, evidence.workflowId, evidence.runId);
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
      cancelActiveActivity();
      Workflow.await(() -> "PAUSED".equals(phase) || "HANDED_OFF".equals(phase));
    } else if ("resume".equals(evidence.action)) {
      pauseRequested = false;
      phase = pausedFrom;
      pausedFrom = null;
    } else {
      handoffRequested = true;
      pauseRequested = false;
      cancelActiveActivity();
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
    validateExecutionIdentity(
        evidence.repository, evidence.releaseDigest, evidence.workflowId, evidence.runId);
    if (!identity.candidate.tag.equals(evidence.tag)
        || !identity.candidate.commitSha.equals(evidence.commitSha)) {
      throw new IllegalArgumentException("Control evidence does not match the exact tag and SHA.");
    }
    if ("resume".equals(evidence.action) && !"PAUSED".equals(phase)) {
      throw new IllegalStateException("Only a paused release can resume.");
    }
  }

  @Override
  public ReleaseStatus status() {
    return new ReleaseStatus(
        phase, identity, approvalRequest, approval, control, pausedFrom, lastCompletedStage);
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
    while (!handoffRequested) {
      handlePause(stage);
      if (handoffRequested) {
        return;
      }
      phase = stage;
      upsertStatus();
      activeActivity = Workflow.newCancellationScope(action);
      try {
        activeActivity.run();
        activeActivity = null;
        lastCompletedStage = stage;
        upsertStatus();
        return;
      } catch (RuntimeException e) {
        activeActivity = null;
        if (!pauseRequested && !handoffRequested) {
          throw e;
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

  private void cancelActiveActivity() {
    if (activeActivity != null) {
      activeActivity.cancel("Authenticated release control requested cancellation.");
    }
  }

  private void validateExecutionIdentity(
      String repository, String releaseDigest, String workflowId, String runId) {
    if (!identity.candidate.repository.equals(repository)
        || !identity.digest().equals(releaseDigest)
        || !Workflow.getInfo().getWorkflowId().equals(workflowId)
        || !Workflow.getInfo().getRunId().equals(runId)) {
      throw new IllegalArgumentException("Evidence does not identify this exact release run.");
    }
  }

  private void upsertStatus() {
    Workflow.upsertMemo(Collections.singletonMap(STATUS_MEMO_KEY, status()));
  }
}
