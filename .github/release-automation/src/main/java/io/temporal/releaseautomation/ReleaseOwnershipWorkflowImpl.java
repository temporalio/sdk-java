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
    status = new OwnershipStatus(claim, Workflow.currentTimeMillis());
    upsertStatus();
    return status;
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
