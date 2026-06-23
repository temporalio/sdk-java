package io.temporal.internal.activity;

import io.temporal.activity.ActivityCancellationToken;
import io.temporal.client.ActivityCanceledException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

final class ActivityCancellationTokenImpl implements ActivityCancellationToken {
  private final CompletableFuture<Void> cancellationFuture = new CompletableFuture<>();
  private volatile ActivityCanceledException cancellationException;

  @Override
  public boolean isCancellationRequested() {
    return cancellationException != null;
  }

  @Override
  public void throwIfCancellationRequested() throws ActivityCanceledException {
    ActivityCanceledException exception = cancellationException;
    if (exception != null) {
      throw exception;
    }
  }

  @Override
  public CompletableFuture<Void> getCancellationFuture() {
    CompletableFuture<Void> result = new CompletableFuture<>();
    cancellationFuture.whenComplete(
        (ignored, exception) -> {
          if (exception == null) {
            result.complete(null);
          } else {
            result.completeExceptionally(unwrapCompletionException(exception));
          }
        });
    return result;
  }

  synchronized void requestCancel(ActivityCanceledException exception) {
    if (cancellationException == null) {
      cancellationException = exception;
      cancellationFuture.completeExceptionally(exception);
    }
  }

  private static Throwable unwrapCompletionException(Throwable exception) {
    return exception instanceof CompletionException && exception.getCause() != null
        ? exception.getCause()
        : exception;
  }
}
