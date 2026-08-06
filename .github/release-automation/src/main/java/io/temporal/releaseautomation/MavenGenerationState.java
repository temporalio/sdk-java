package io.temporal.releaseautomation;

public final class MavenGenerationState {
  public int generation;
  public String description;
  public boolean submissionStarted;
  public String sonatypeRepositoryId;
  public String portalDeploymentId;

  public MavenGenerationState() {}

  MavenGenerationState(String releaseDigest, int generation) {
    if (generation < 0 || releaseDigest == null || !releaseDigest.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Maven generation identity is invalid.");
    }
    this.generation = generation;
    this.description = "sdk-java:" + releaseDigest + ":" + generation;
  }

  public void validate(String releaseDigest) {
    if (generation < 0
        || !new MavenGenerationState(releaseDigest, generation).description.equals(description)) {
      throw new IllegalArgumentException("Maven generation description is invalid.");
    }
    if (sonatypeRepositoryId != null
        && !sonatypeRepositoryId.isEmpty()
        && !sonatypeRepositoryId.matches("[A-Za-z0-9._-]+")) {
      throw new IllegalArgumentException("Sonatype repository ID is invalid.");
    }
    if (portalDeploymentId != null
        && !portalDeploymentId.isEmpty()
        && !portalDeploymentId.matches("[0-9a-fA-F-]{16,64}")) {
      throw new IllegalArgumentException("Portal deployment ID is invalid.");
    }
  }
}
