package io.temporal.releaseautomation;

public final class MavenGenerationInspection implements Comparable<MavenGenerationInspection> {
  public int generation;
  public String description;
  public String repositoryId;
  public String repositoryState;
  public String portalDeploymentId;
  public String portalDeploymentState;

  public MavenGenerationInspection() {}

  public void validate(String releaseDigest) {
    MavenGenerationState state = new MavenGenerationState(releaseDigest, generation);
    if (!state.description.equals(description)) {
      throw new IllegalArgumentException("Inspected Maven generation description is invalid.");
    }
    state.sonatypeRepositoryId = repositoryId;
    state.portalDeploymentId = portalDeploymentId;
    state.validate(releaseDigest);
    if (!("absent".equals(repositoryState)
        || "open".equals(repositoryState)
        || "closed".equals(repositoryState)
        || "released".equals(repositoryState))) {
      throw new IllegalArgumentException("Inspected Sonatype repository state is invalid.");
    }
    if (!("".equals(portalDeploymentState)
        || "PENDING".equals(portalDeploymentState)
        || "VALIDATING".equals(portalDeploymentState)
        || "VALIDATED".equals(portalDeploymentState)
        || "PUBLISHING".equals(portalDeploymentState)
        || "PUBLISHED".equals(portalDeploymentState)
        || "FAILED".equals(portalDeploymentState))) {
      throw new IllegalArgumentException("Inspected Portal state is invalid.");
    }
  }

  String canonicalForm() {
    return generation
        + "\n"
        + description
        + "\n"
        + value(repositoryId)
        + "\n"
        + repositoryState
        + "\n"
        + value(portalDeploymentId)
        + "\n"
        + portalDeploymentState;
  }

  @Override
  public int compareTo(MavenGenerationInspection other) {
    return Integer.compare(generation, other.generation);
  }

  private static String value(String value) {
    return value == null ? "" : value;
  }
}
