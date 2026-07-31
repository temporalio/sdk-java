package io.temporal.releaseautomation;

public final class ReleaseResult {
  public String releaseDigest;
  public String githubReleaseUrl;
  public String mavenCentralUrl;

  public ReleaseResult() {}

  public ReleaseResult(String releaseDigest, String githubReleaseUrl, String mavenCentralUrl) {
    this.releaseDigest = releaseDigest;
    this.githubReleaseUrl = githubReleaseUrl;
    this.mavenCentralUrl = mavenCentralUrl;
  }
}
