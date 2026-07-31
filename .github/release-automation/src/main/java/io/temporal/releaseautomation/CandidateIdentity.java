package io.temporal.releaseautomation;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public final class CandidateIdentity {
  public static final String REPOSITORY = ReleasePolicy.REPOSITORY;
  private static final Pattern TAG = Pattern.compile("v[0-9]+\\.[0-9]+\\.[0-9]+(?:-RC[0-9]+)?");
  private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");
  private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

  public String repository;
  public String version;
  public String tag;
  public String commitSha;
  public String releaseNotesPath;
  public String releaseNotesSha256;

  public CandidateIdentity() {}

  public CandidateIdentity(
      String repository,
      String version,
      String tag,
      String commitSha,
      String releaseNotesPath,
      String releaseNotesSha256) {
    this.repository = repository;
    this.version = version;
    this.tag = tag;
    this.commitSha = commitSha.toLowerCase(Locale.ROOT);
    this.releaseNotesPath = releaseNotesPath;
    this.releaseNotesSha256 = releaseNotesSha256.toLowerCase(Locale.ROOT);
    validate();
  }

  public void validate() {
    require(REPOSITORY.equals(repository), "Only temporalio/sdk-java releases are supported.");
    require(tag != null && TAG.matcher(tag).matches(), "Invalid release tag.");
    require(Objects.equals(version, tag.substring(1)), "Version must equal the tag without 'v'.");
    require(commitSha != null && SHA.matcher(commitSha).matches(), "Commit must be a full SHA.");
    require(
        Objects.equals(releaseNotesPath, "releases/" + tag),
        "Release notes path must be releases/<tag>.");
    require(
        releaseNotesSha256 != null && HASH.matcher(releaseNotesSha256).matches(),
        "Release notes hash must be SHA-256.");
  }

  public String canonicalForm() {
    validate();
    return repository
        + "\n"
        + version
        + "\n"
        + tag
        + "\n"
        + commitSha
        + "\n"
        + releaseNotesPath
        + "\n"
        + releaseNotesSha256;
  }

  public String digest() {
    return Digests.sha256(canonicalForm());
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalArgumentException(message);
    }
  }
}
