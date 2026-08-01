package io.temporal.releaseautomation;

public final class ControlEvidence {
  public String action;
  public String repository;
  public String releaseDigest;
  public String workflowId;
  public String runId;
  public long githubRunId;
  public String githubActor;
  public String tag;
  public String commitSha;
  public String reason;
  public long recordedAtMillis;
  public String githubReleaseUrl;
  public String mavenCentralUrl;

  public ControlEvidence() {}

  public ControlEvidence(
      String action,
      String repository,
      String releaseDigest,
      String workflowId,
      String runId,
      long githubRunId,
      String githubActor,
      String tag,
      String commitSha,
      String reason) {
    this.action = action;
    this.repository = repository;
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
            || "retry-maven-submission".equals(action)
            || "manual-complete".equals(action))
        || !ReleasePolicy.REPOSITORY.equals(repository)
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
    if ("manual-complete".equals(action)
        && (!(githubReleaseUrl != null
                && githubReleaseUrl.matches(
                    "https://github\\.com/temporalio/sdk-java/releases/tag/v.+"))
            || !(mavenCentralUrl != null
                && mavenCentralUrl.startsWith(
                    "https://central.sonatype.com/artifact/io.temporal/temporal-sdk/")))) {
      throw new IllegalArgumentException("Manual completion URLs are invalid.");
    }
  }
}
