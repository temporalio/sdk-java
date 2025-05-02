package io.temporal.client;

import io.temporal.activity.ActivityInfo;
import io.temporal.worker.WorkerFactory;
import java.util.concurrent.TimeUnit;

/**
 * Indicates that {@link WorkerFactory#shutdown()} or {@link WorkerFactory#shutdownNow()} was
 * called. It is OK to ignore the exception to let the activity complete. It assumes that {@link
 * WorkerFactory#awaitTermination(long, TimeUnit)} is called with a timeout larger than the activity
 * execution time.
 */
public final class ActivityWorkerShutdownException extends ActivityCompletionException {

  public ActivityWorkerShutdownException(ActivityInfo info) {
    super(info);
  }

  public ActivityWorkerShutdownException() {
    super();
  }
}
