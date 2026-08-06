package io.temporal.releaseautomation;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class GithubArtifactReceipt implements Comparable<GithubArtifactReceipt> {
  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
  private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");

  public long artifactId;
  public long workflowRunId;
  public String artifactName;
  public String githubDigest;
  public String createdAt;
  public String expiresAt;
  public List<ArtifactEntry> files = new ArrayList<>();

  public GithubArtifactReceipt() {}

  public GithubArtifactReceipt(
      long artifactId,
      long workflowRunId,
      String artifactName,
      String githubDigest,
      String createdAt,
      String expiresAt,
      List<ArtifactEntry> files) {
    this.artifactId = artifactId;
    this.workflowRunId = workflowRunId;
    this.artifactName = artifactName;
    this.githubDigest = githubDigest;
    this.createdAt = createdAt;
    this.expiresAt = expiresAt;
    this.files = new ArrayList<>(files);
    validate();
  }

  public void validate() {
    if (artifactId <= 0 || workflowRunId <= 0) {
      throw new IllegalArgumentException("GitHub artifact and run IDs must be positive.");
    }
    if (artifactName == null || !NAME.matcher(artifactName).matches()) {
      throw new IllegalArgumentException("GitHub artifact name is invalid.");
    }
    if (githubDigest == null || !DIGEST.matcher(githubDigest).matches()) {
      throw new IllegalArgumentException("GitHub artifact digest must be SHA-256.");
    }
    Instant created = parseInstant(createdAt, "creation");
    Instant expires = parseInstant(expiresAt, "expiration");
    if (!expires.isAfter(created)) {
      throw new IllegalArgumentException("GitHub artifact expiration must follow creation.");
    }
    if (files == null || files.isEmpty()) {
      throw new IllegalArgumentException("GitHub artifact must contain expected files.");
    }
    Set<String> names = new HashSet<>();
    for (ArtifactEntry file : files) {
      file.validate();
      if (!names.add(file.name)) {
        throw new IllegalArgumentException("Duplicate GitHub artifact filename: " + file.name);
      }
    }
  }

  public String canonicalForm() {
    validate();
    List<ArtifactEntry> sorted = new ArrayList<>(files);
    Collections.sort(sorted);
    StringBuilder result =
        new StringBuilder()
            .append(artifactId)
            .append('\n')
            .append(workflowRunId)
            .append('\n')
            .append(artifactName)
            .append('\n')
            .append(githubDigest)
            .append('\n')
            .append(createdAt)
            .append('\n')
            .append(expiresAt)
            .append('\n');
    for (ArtifactEntry file : sorted) {
      result.append(file.canonicalForm()).append('\n');
    }
    return result.toString();
  }

  @Override
  public int compareTo(GithubArtifactReceipt other) {
    return artifactName.compareTo(other.artifactName);
  }

  private static Instant parseInstant(String value, String field) {
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException | NullPointerException e) {
      throw new IllegalArgumentException("GitHub artifact " + field + " time is invalid.", e);
    }
  }
}
