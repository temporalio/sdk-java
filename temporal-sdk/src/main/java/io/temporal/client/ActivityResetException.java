package io.temporal.client;

import io.temporal.activity.ActivityInfo;
import io.temporal.common.Experimental;

/***
 * Indicates that the activity attempt was reset by the user.
 *
 * <p>Catching this exception directly is discouraged and catching the parent class {@link ActivityCompletionException} is recommended instead.<br>
 */
@Experimental
public final class ActivityResetException extends ActivityCompletionException {
  public ActivityResetException(ActivityInfo info) {
    super(info);
  }

  public ActivityResetException() {
    super();
  }
}
