package io.temporal.client;

import io.temporal.common.Experimental;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A single page of results from {@link ActivityClient#listExecutionsPaginated}. Use {@link
 * #getNextPageToken()} to fetch the next page; when it is {@code null}, there are no more pages.
 */
@Experimental
public final class ActivityListPage {

  private final List<ActivityExecutionMetadata> activities;
  private final @Nullable byte[] nextPageToken;

  public ActivityListPage(
      List<ActivityExecutionMetadata> activities, @Nullable byte[] nextPageToken) {
    this.activities = activities;
    this.nextPageToken = nextPageToken;
  }

  /** Activities on this page. */
  @Nonnull
  public List<ActivityExecutionMetadata> getActivities() {
    return activities;
  }

  /**
   * Token to pass to the next {@link ActivityClient#listExecutionsPaginated} call, or {@code null}
   * if this is the last page.
   */
  @Nullable
  public byte[] getNextPageToken() {
    return nextPageToken;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ActivityListPage that = (ActivityListPage) o;
    return Objects.equals(activities, that.activities)
        && Arrays.equals(nextPageToken, that.nextPageToken);
  }

  @Override
  public int hashCode() {
    int result = Objects.hash(activities);
    result = 31 * result + Arrays.hashCode(nextPageToken);
    return result;
  }

  @Override
  public String toString() {
    return "ActivityListPage{"
        + "activities="
        + activities
        + ", nextPageToken="
        + Arrays.toString(nextPageToken)
        + '}';
  }
}
