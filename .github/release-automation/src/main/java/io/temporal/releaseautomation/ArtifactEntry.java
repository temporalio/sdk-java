package io.temporal.releaseautomation;

import java.util.Locale;
import java.util.regex.Pattern;

public final class ArtifactEntry implements Comparable<ArtifactEntry> {
  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
  private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

  public String name;
  public String sha256;
  public long size;

  public ArtifactEntry() {}

  public ArtifactEntry(String name, String sha256, long size) {
    this.name = name;
    this.sha256 = sha256.toLowerCase(Locale.ROOT);
    this.size = size;
    validate();
  }

  public void validate() {
    if (name == null || !NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("Artifact name must be a basename.");
    }
    if (sha256 == null || !HASH.matcher(sha256).matches()) {
      throw new IllegalArgumentException("Artifact hash must be SHA-256.");
    }
    if (size <= 0) {
      throw new IllegalArgumentException("Artifact size must be positive.");
    }
  }

  String canonicalForm() {
    validate();
    return name + "\t" + sha256 + "\t" + size;
  }

  @Override
  public int compareTo(ArtifactEntry other) {
    return name.compareTo(other.name);
  }
}
