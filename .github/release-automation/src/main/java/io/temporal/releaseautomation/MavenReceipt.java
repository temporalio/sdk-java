package io.temporal.releaseautomation;

public final class MavenReceipt {
  public String mavenCentralUrl;
  public String sonatypeRepositoryId;
  public String portalDeploymentId;

  public MavenReceipt() {}

  public MavenReceipt(String mavenCentralUrl, String sonatypeRepositoryId) {
    this(mavenCentralUrl, sonatypeRepositoryId, null);
  }

  public MavenReceipt(
      String mavenCentralUrl, String sonatypeRepositoryId, String portalDeploymentId) {
    this.mavenCentralUrl = mavenCentralUrl;
    this.sonatypeRepositoryId = sonatypeRepositoryId;
    this.portalDeploymentId = portalDeploymentId;
  }
}
