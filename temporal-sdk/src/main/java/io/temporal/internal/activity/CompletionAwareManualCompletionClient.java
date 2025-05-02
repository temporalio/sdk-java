package io.temporal.internal.activity;

import io.temporal.activity.ManualActivityCompletionClient;
import io.temporal.failure.CanceledFailure;
import io.temporal.workflow.Functions;
import javax.annotation.Nonnull;

final class CompletionAwareManualCompletionClient implements ManualActivityCompletionClient {
  private final ManualActivityCompletionClient client;
  private final Functions.Proc completionHandle;

  CompletionAwareManualCompletionClient(
      ManualActivityCompletionClient client, Functions.Proc completionHandle) {
    this.client = client;
    this.completionHandle = completionHandle;
  }

  @Override
  public void complete(Object result) {
    try {
      client.complete(result);
    } finally {
      completionHandle.apply();
    }
  }

  @Override
  public void fail(@Nonnull Throwable failure) {
    try {
      client.fail(failure);
    } finally {
      completionHandle.apply();
    }
  }

  @Override
  public void recordHeartbeat(Object details) throws CanceledFailure {
    client.recordHeartbeat(details);
  }

  @Override
  public void reportCancellation(Object details) {
    try {
      client.reportCancellation(details);
    } finally {
      completionHandle.apply();
    }
  }
}
