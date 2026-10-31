package io.temporal.client;

import io.temporal.activity.ActivityInfo;

/***
 * Indicates that the activity attempt was reset by the user.
 *
 * <p>Catching this exception directly is discouraged and catching the parent class {@link ActivityCompletionException} is recommended instead.<br>
 */
public final class ActivityResetException extends ActivityCompletionException {
  public ActivityResetException(ActivityInfo info) {
    super(info);
  }

  public ActivityResetException() {
    super();
  }
}
