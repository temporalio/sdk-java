package io.temporal.releaseautomation;

public final class ControlEvidence {
  public String action;
  public String releaseDigest;
  public String workflowId;
  public String runId;
  public long githubRunId;
  public String githubActor;
  public String tag;
  public String commitSha;
  public String reason;
  public long recordedAtMillis;
  public int mavenSubmissionGeneration = -1;
  public String authorizationSha256;
  public MavenInspection mavenInspection;
  public boolean manualMavenRequested;

  public ControlEvidence() {}

  public ControlEvidence(
      String action,
      String releaseDigest,
      String workflowId,
      String runId,
      long githubRunId,
      String githubActor,
      String tag,
      String commitSha,
      String reason) {
    this.action = action;
    this.releaseDigest = releaseDigest;
    this.workflowId = workflowId;
    this.runId = runId;
    this.githubRunId = githubRunId;
    this.githubActor = githubActor;
    this.tag = tag;
    this.commitSha = commitSha;
    this.reason = reason;
    validate();
  }

  public void validate() {
    if (!("pause".equals(action)
            || "resume".equals(action)
            || "handoff-manual".equals(action)
            || "retry-maven-submission".equals(action))
        || releaseDigest == null
        || !releaseDigest.matches("[0-9a-f]{64}")
        || workflowId == null
        || !workflowId.matches("sdk-java-release/[0-9a-f]{64}")
        || runId == null
        || !runId.matches("[0-9a-fA-F-]{16,64}")
        || githubRunId <= 0
        || githubActor == null
        || !githubActor.matches("[A-Za-z0-9-]{1,39}")
        || tag == null
        || !tag.matches("v[0-9]+\\.[0-9]+\\.[0-9]+(?:-RC[0-9]+)?")
        || commitSha == null
        || !commitSha.matches("[0-9a-f]{40}")
        || reason == null
        || reason.isEmpty()) {
      throw new IllegalArgumentException("Invalid authenticated release control evidence.");
    }
    if ("retry-maven-submission".equals(action)
        && (mavenSubmissionGeneration <= 0
            || authorizationSha256 == null
            || !authorizationSha256.matches("[0-9a-f]{64}")
            || mavenInspection == null)) {
      throw new IllegalArgumentException(
          "Maven retry control requires an exact authorized inspection.");
    }
    if ("retry-maven-submission".equals(action)) {
      mavenInspection.validate(releaseDigest);
      if (!Digests.sha256(mavenInspection.canonicalForm(releaseDigest))
          .equals(authorizationSha256)) {
        throw new IllegalArgumentException("Maven retry inspection digest differs.");
      }
    }
    if (manualMavenRequested && !"handoff-manual".equals(action)) {
      throw new IllegalArgumentException("Manual Maven intent is only valid for a manual handoff.");
    }
  }
}
