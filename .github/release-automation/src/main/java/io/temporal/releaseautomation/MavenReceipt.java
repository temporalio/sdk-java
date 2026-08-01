package io.temporal.releaseautomation;

public final class MavenReceipt {
  public String mavenCentralUrl;
  public String sonatypeRepositoryId;

  public MavenReceipt() {}

  public MavenReceipt(String mavenCentralUrl, String sonatypeRepositoryId) {
    this.mavenCentralUrl = mavenCentralUrl;
    this.sonatypeRepositoryId = sonatypeRepositoryId;
  }
}
