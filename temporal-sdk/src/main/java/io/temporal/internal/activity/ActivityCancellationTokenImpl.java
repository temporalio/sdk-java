package io.temporal.internal.activity;

import io.temporal.activity.ActivityCancellationToken;
import io.temporal.client.ActivityCanceledException;
import java.util.concurrent.CompletableFuture;

final class ActivityCancellationTokenImpl implements ActivityCancellationToken {
  private final CompletableFuture<Void> cancellationRequest = new CompletableFuture<>();
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
  public CompletableFuture<Void> getCancellationRequest() {
    return cancellationRequest;
  }

  void requestCancel(ActivityCanceledException exception) {
    cancellationException = exception;
    cancellationRequest.complete(null);
  }
}
