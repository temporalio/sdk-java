package io.temporal.client;

import io.temporal.common.Experimental;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A single page of results from {@link WorkflowClient#listActivitiesPaginated}. Use {@link
 * #getNextPageToken()} to fetch the next page; when it is {@code null}, there are no more pages.
 */
@Experimental
public final class ActivityListPage {

  private final List<ActivityExecution> activities;
  private final @Nullable byte[] nextPageToken;

  public ActivityListPage(List<ActivityExecution> activities, @Nullable byte[] nextPageToken) {
    this.activities = activities;
    this.nextPageToken = nextPageToken;
  }

  /** Activities on this page. */
  @Nonnull
  public List<ActivityExecution> getActivities() {
    return activities;
  }

  /**
   * Token to pass to the next {@code listActivitiesPaginated} call, or {@code null} if this is the
   * last page.
   */
  @Nullable
  public byte[] getNextPageToken() {
    return nextPageToken;
  }
}
