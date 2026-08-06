package io.temporal.releaseautomation;

public final class ManualMavenAttempt {
  public String state;
  public String tag;
  public String commitSha;
  public String releaseDigest;
  public String githubActor;
  public long githubRunId;

  public ManualMavenAttempt() {}

  ManualMavenAttempt(
      String state,
      String tag,
      String commitSha,
      String releaseDigest,
      String githubActor,
      long githubRunId) {
    this.state = state;
    this.tag = tag;
    this.commitSha = commitSha;
    this.releaseDigest = releaseDigest;
    this.githubActor = githubActor;
    this.githubRunId = githubRunId;
    validate();
  }

  public void validate() {
    if (!("STARTED".equals(state) || "COMPLETED".equals(state))
        || tag == null
        || !tag.matches("v[0-9]+\\.[0-9]+\\.[0-9]+(?:-RC[0-9]+)?")
        || commitSha == null
        || !commitSha.matches("[0-9a-f]{40}")
        || releaseDigest == null
        || !releaseDigest.matches("[0-9a-f]{64}")
        || githubActor == null
        || !githubActor.matches("[A-Za-z0-9-]{1,39}")
        || githubRunId <= 0) {
      throw new IllegalArgumentException("Invalid manual Maven attempt evidence.");
    }
  }
}
