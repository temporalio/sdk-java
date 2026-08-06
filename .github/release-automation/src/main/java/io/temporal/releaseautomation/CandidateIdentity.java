package io.temporal.releaseautomation;

import java.util.Locale;
import java.util.regex.Pattern;

public final class CandidateIdentity {
  public static final String REPOSITORY = ReleasePolicy.REPOSITORY;
  private static final Pattern TAG = Pattern.compile("v[0-9]+\\.[0-9]+\\.[0-9]+(?:-RC[0-9]+)?");
  private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");
  private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

  public String tag;
  public String commitSha;
  public String releaseNotesSha256;
  public String trustedAutomationCommit;
  public String mavenPolicy;

  public CandidateIdentity() {}

  public CandidateIdentity(
      String tag,
      String commitSha,
      String releaseNotesSha256,
      String trustedAutomationCommit,
      String mavenPolicy) {
    this.tag = tag;
    this.commitSha = commitSha.toLowerCase(Locale.ROOT);
    this.releaseNotesSha256 = releaseNotesSha256.toLowerCase(Locale.ROOT);
    this.trustedAutomationCommit = trustedAutomationCommit.toLowerCase(Locale.ROOT);
    this.mavenPolicy = mavenPolicy;
    validate();
  }

  public void validate() {
    require(tag != null && TAG.matcher(tag).matches(), "Invalid release tag.");
    require(commitSha != null && SHA.matcher(commitSha).matches(), "Commit must be a full SHA.");
    require(
        releaseNotesSha256 != null && HASH.matcher(releaseNotesSha256).matches(),
        "Release notes hash must be SHA-256.");
    require(
        trustedAutomationCommit != null && SHA.matcher(trustedAutomationCommit).matches(),
        "Trusted automation commit must be a full SHA.");
    ReleasePolicy.mavenArtifacts(mavenPolicy);
  }

  public String canonicalForm() {
    validate();
    return REPOSITORY
        + "\n"
        + version()
        + "\n"
        + tag
        + "\n"
        + commitSha
        + "\n"
        + releaseNotesPath()
        + "\n"
        + releaseNotesSha256
        + "\n"
        + trustedAutomationCommit
        + "\n"
        + mavenPolicy;
  }

  public String digest() {
    return Digests.sha256(canonicalForm());
  }

  public String version() {
    return tag.substring(1);
  }

  public String releaseNotesPath() {
    return "releases/" + tag;
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalArgumentException(message);
    }
  }
}
