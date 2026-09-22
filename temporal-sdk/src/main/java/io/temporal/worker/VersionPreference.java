package io.temporal.worker;

import io.temporal.common.Experimental;
import java.util.Objects;

/** Version preference returned by {@link PreferredVersionProvider}. */
@Experimental
public final class VersionPreference {
  private final int version;
  private final boolean clampToSupportedRange;

  private VersionPreference(int version, boolean clampToSupportedRange) {
    this.version = version;
    this.clampToSupportedRange = clampToSupportedRange;
  }

  /**
   * Creates a preference for {@code version}. If the version is outside the supported range for the
   * call, the SDK fails the workflow task unless {@link #clampToSupportedRange()} is used.
   */
  public static VersionPreference of(int version) {
    return new VersionPreference(version, false);
  }

  /**
   * Returns a preference that clamps the version into the call's supported range before recording.
   */
  public VersionPreference clampToSupportedRange() {
    return new VersionPreference(version, true);
  }

  public int getVersion() {
    return version;
  }

  public boolean isClampToSupportedRange() {
    return clampToSupportedRange;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    VersionPreference that = (VersionPreference) o;
    return version == that.version && clampToSupportedRange == that.clampToSupportedRange;
  }

  @Override
  public int hashCode() {
    return Objects.hash(version, clampToSupportedRange);
  }

  @Override
  public String toString() {
    return "VersionPreference{"
        + "version="
        + version
        + ", clampToSupportedRange="
        + clampToSupportedRange
        + '}';
  }
}
