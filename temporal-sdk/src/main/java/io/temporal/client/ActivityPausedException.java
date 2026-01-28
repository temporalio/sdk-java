package io.temporal.client;

import io.temporal.activity.ActivityInfo;
import io.temporal.common.Experimental;

/***
 * Indicates that the activity was paused by the user.
 *
 * <p>Catching this exception directly is discouraged and catching the parent class {@link ActivityCompletionException} is recommended instead.<br>
 */
@Experimental
public final class ActivityPausedException extends ActivityCompletionException {
  public ActivityPausedException(ActivityInfo info) {
    super(info);
  }

  public ActivityPausedException() {
    super();
  }
}
