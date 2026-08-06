package io.temporal.releaseautomation;

import io.temporal.workflow.UpdateValidatorMethod;
import io.temporal.workflow.Workflow;
import java.util.Collections;

public final class ReleaseOwnershipWorkflowImpl implements ReleaseOwnershipWorkflow {
  static final String STATUS_MEMO_KEY = "ReleaseOwnership";
  private OwnershipStatus status;

  @Override
  public void manage(OwnershipClaim initialClaim) {
    initialClaim.validate();
    status = new OwnershipStatus(initialClaim, Workflow.currentTimeMillis());
    upsertStatus();
    Workflow.await(() -> false);
  }

  @Override
  public OwnershipStatus claim(OwnershipClaim claim) {
    validateClaim(claim);
    if ("MANUAL".equals(status.owner) && "TEMPORAL".equals(claim.owner)) {
      return status;
    }
    OwnershipStatus updated = new OwnershipStatus(claim, Workflow.currentTimeMillis());
    if ("MANUAL".equals(status.owner) && "MANUAL".equals(claim.owner)) {
      updated.manualMavenState = status.manualMavenState;
      updated.manualMavenActor = status.manualMavenActor;
      updated.manualMavenRunId = status.manualMavenRunId;
    }
    status = updated;
    upsertStatus();
    return status;
  }

  @Override
  public OwnershipStatus recordManualMaven(ManualMavenAttempt attempt) {
    validateManualMaven(attempt);
    status.manualMavenState = attempt.state;
    status.manualMavenActor = attempt.githubActor;
    status.manualMavenRunId = attempt.githubRunId;
    status.recordedAtMillis = Workflow.currentTimeMillis();
    upsertStatus();
    return status;
  }

  @UpdateValidatorMethod(updateName = "recordManualMaven")
  public void validateManualMaven(ManualMavenAttempt attempt) {
    attempt.validate();
    if (status == null
        || !"MANUAL".equals(status.owner)
        || !status.tag.equals(attempt.tag)
        || !status.commitSha.equals(attempt.commitSha)
        || !status.releaseDigest.equals(attempt.releaseDigest)) {
      throw new IllegalArgumentException(
          "Manual Maven evidence does not match the durable release ownership.");
    }
    if ("STARTED".equals(attempt.state)) {
      if (!"NOT_STARTED".equals(status.manualMavenState)) {
        throw new IllegalStateException(
            "Manual Maven publication already started; inspect remote state before continuing.");
      }
    } else if (!"STARTED".equals(status.manualMavenState)
        || !attempt.githubActor.equals(status.manualMavenActor)
        || attempt.githubRunId != status.manualMavenRunId) {
      throw new IllegalStateException(
          "Only the exact GitHub run that started manual Maven publication may complete it.");
    }
  }

  @UpdateValidatorMethod(updateName = "claim")
  public void validateClaim(OwnershipClaim claim) {
    claim.validate();
    if (status == null) {
      throw new IllegalStateException("Ownership Workflow is not initialized.");
    }
    if (!status.tag.equals(claim.tag) || !status.commitSha.equals(claim.commitSha)) {
      throw new IllegalArgumentException("The release tag is already owned by another commit.");
    }
    if ("TEMPORAL".equals(status.owner)
        && "MANUAL".equals(claim.owner)
        && (!claim.handoffConfirmed || !status.releaseDigest.equals(claim.releaseDigest))) {
      throw new IllegalArgumentException(
          "Manual takeover requires the exact Temporal handoff result.");
    }
    if (status.owner.equals(claim.owner)
        && !sameOrUnspecifiedManualDigest(status.releaseDigest, claim.releaseDigest, claim.owner)) {
      throw new IllegalArgumentException("The ownership release digest cannot change.");
    }
  }

  @Override
  public OwnershipStatus status() {
    return status;
  }

  private void upsertStatus() {
    Workflow.upsertMemo(Collections.singletonMap(STATUS_MEMO_KEY, status));
  }

  private static boolean sameOrUnspecifiedManualDigest(
      String current, String requested, String owner) {
    if (current == null ? requested == null : current.equals(requested)) {
      return true;
    }
    return "MANUAL".equals(owner)
        && (current == null || current.isEmpty())
        && requested != null
        && !requested.isEmpty();
  }
}
