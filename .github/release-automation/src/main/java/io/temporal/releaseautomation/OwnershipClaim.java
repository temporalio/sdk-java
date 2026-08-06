package io.temporal.releaseautomation;

public final class OwnershipClaim {
  public String tag;
  public String commitSha;
  public String releaseDigest;
  public String owner;
  public String githubActor;
  public long githubRunId;
  public boolean handoffConfirmed;

  public OwnershipClaim() {}

  static OwnershipClaim temporal(ReleaseIdentity release) {
    OwnershipClaim claim = new OwnershipClaim();
    claim.tag = release.candidate.tag;
    claim.commitSha = release.candidate.commitSha;
    claim.releaseDigest = release.digest();
    claim.owner = "TEMPORAL";
    claim.validate();
    return claim;
  }

  static OwnershipClaim manual(
      String tag,
      String commitSha,
      String releaseDigest,
      String githubActor,
      long githubRunId,
      boolean handoffConfirmed) {
    OwnershipClaim claim = new OwnershipClaim();
    claim.tag = tag;
    claim.commitSha = commitSha;
    claim.releaseDigest = releaseDigest;
    claim.owner = "MANUAL";
    claim.githubActor = githubActor;
    claim.githubRunId = githubRunId;
    claim.handoffConfirmed = handoffConfirmed;
    claim.validate();
    return claim;
  }

  public void validate() {
    if (tag == null || !tag.matches("v[0-9]+\\.[0-9]+\\.[0-9]+(?:-RC[0-9]+)?")) {
      throw new IllegalArgumentException("Ownership tag is invalid.");
    }
    if (commitSha == null || !commitSha.matches("[0-9a-f]{40}")) {
      throw new IllegalArgumentException("Ownership commit must be a full SHA.");
    }
    if (!("TEMPORAL".equals(owner) || "MANUAL".equals(owner))) {
      throw new IllegalArgumentException("Ownership controller is invalid.");
    }
    if ("TEMPORAL".equals(owner)
        && (releaseDigest == null || !releaseDigest.matches("[0-9a-f]{64}"))) {
      throw new IllegalArgumentException("Temporal ownership requires a release digest.");
    }
    if ("MANUAL".equals(owner)
        && (githubActor == null || githubActor.isEmpty() || githubRunId <= 0)) {
      throw new IllegalArgumentException("Manual ownership requires its authenticated GitHub run.");
    }
    if ("MANUAL".equals(owner)
        && releaseDigest != null
        && !releaseDigest.isEmpty()
        && !releaseDigest.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Manual ownership release digest is invalid.");
    }
    if (handoffConfirmed && (releaseDigest == null || releaseDigest.isEmpty())) {
      throw new IllegalArgumentException("A confirmed handoff requires the exact release digest.");
    }
  }
}
