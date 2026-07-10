package io.temporal.worker;

import io.temporal.common.Experimental;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Provides the version to record for a {@link io.temporal.workflow.Workflow#getVersion(String, int,
 * int)} call when the version marker is created for the first time.
 *
 * <p>This provider is called only during non-replay workflow task execution, before the SDK records
 * a version marker for the change ID. It is not called during replay or after a version was already
 * memoized for the same change ID.
 */
@Experimental
@FunctionalInterface
public interface PreferredVersionProvider {

  /**
   * Returns the preferred version for the supplied {@code getVersion} call, or {@code null} to use
   * the SDK default of {@code maxSupported}.
   *
   * <p>This method is invoked on the workflow thread. Any exception it throws propagates out of the
   * {@code getVersion} call and fails the current workflow task, which will be retried; a provider
   * that always throws will therefore block the workflow.
   */
  @Nullable
  VersionPreference getPreferredVersion(@Nonnull PreferredVersionProviderInput input);
}
