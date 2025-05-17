package io.temporal.internal.activity;

import io.temporal.client.ActivityCompletionException;
import java.lang.reflect.Type;
import java.util.Optional;

interface HeartbeatContext {

  /**
   * @see io.temporal.activity.ActivityExecutionContext#heartbeat(Object)
   */
  <V> void heartbeat(V details) throws ActivityCompletionException;

  /**
   * @see io.temporal.activity.ActivityExecutionContext#getHeartbeatDetails(Class, Type)
   */
  <V> Optional<V> getHeartbeatDetails(Class<V> detailsClass, Type detailsGenericType);

  Object getLastHeartbeatDetails();

  /** Cancel any pending heartbeat and discard cached heartbeat details. */
  void cancelOutstandingHeartbeat();
}
