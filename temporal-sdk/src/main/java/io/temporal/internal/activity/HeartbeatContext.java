package io.temporal.internal.activity;

import io.temporal.activity.ActivityCancellationToken;
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

  /**
   * @see io.temporal.activity.ActivityExecutionContext#getLastHeartbeatDetails(Class)
   */
  <V> Optional<V> getLastHeartbeatDetails(Class<V> detailsClass, Type detailsGenericType);

  Object getLatestHeartbeatDetails();

  ActivityCancellationToken getCancellationToken();

  /** Mark this activity as canceled by an external worker command. */
  void cancelFromWorkerCommand();

  /** Mark this activity as returned for async completion. */
  void asyncCompletionStarted();

  /** Cancel any pending heartbeat and discard cached heartbeat details. */
  void cancelOutstandingHeartbeat();
}
