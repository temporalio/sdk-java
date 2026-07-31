package io.temporal.releaseautomation;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.UpdateValidatorMethod;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.Collections;

public final class ReleaseWorkflowImpl implements ReleaseWorkflow {
  static final String STATUS_MEMO_KEY = "ReleaseStatus";
  private ReleaseIdentity identity;
  private ApprovalEvidence approval;
  private String phase = "INITIALIZING";

  @Override
  public ReleaseResult release(ReleaseIdentity releaseIdentity) {
    releaseIdentity.validate();
    identity = releaseIdentity;
    phase = "AWAITING_APPROVAL";
    upsertStatus();
    Workflow.await(() -> approval != null);
    phase = "PUBLISHING";
    upsertStatus();

    PublicationActivities activities =
        Workflow.newActivityStub(
            PublicationActivities.class,
            ActivityOptions.newBuilder()
                .setTaskQueue(QueueNames.publication(identity))
                .setStartToCloseTimeout(Duration.ofHours(2))
                .setRetryOptions(
                    RetryOptions.newBuilder()
                        .setInitialInterval(Duration.ofMinutes(2))
                        .setMaximumInterval(Duration.ofMinutes(15))
                        .setDoNotRetry("ReleaseIdentityConflict", "InvalidApproval")
                        .build())
                .build());
    ReleaseResult result =
        activities.reconcileAndPublish(
            new PublicationInput(
                identity,
                approval,
                Workflow.getInfo().getWorkflowId(),
                Workflow.getInfo().getRunId()));
    phase = "PUBLISHED";
    upsertStatus();
    return result;
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
    if (identity == null || !"AWAITING_APPROVAL".equals(phase)) {
      throw new IllegalStateException("The release is not awaiting approval.");
    }
    evidence.validate();
    if (!identity.candidate.repository.equals(evidence.repository)
        || !identity.digest().equals(evidence.releaseDigest)
        || !Workflow.getInfo().getWorkflowId().equals(evidence.workflowId)
        || !Workflow.getInfo().getRunId().equals(evidence.runId)) {
      throw new IllegalArgumentException("Approval does not identify this exact release run.");
    }
  }

  @Override
  public ReleaseStatus status() {
    return new ReleaseStatus(phase, identity, approval);
  }

  private void upsertStatus() {
    Workflow.upsertMemo(Collections.singletonMap(STATUS_MEMO_KEY, status()));
  }
}
