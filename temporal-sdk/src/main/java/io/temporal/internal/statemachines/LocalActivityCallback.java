package io.temporal.internal.statemachines;

import io.temporal.api.common.v1.Payloads;
import io.temporal.api.failure.v1.Failure;
import io.temporal.workflow.Functions;
import java.time.Duration;
import java.util.Optional;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

@FunctionalInterface
public interface LocalActivityCallback
    extends Functions.Proc2<
        LocalActivityCallback.Output, LocalActivityCallback.LocalActivityFailedException> {

  @Override
  void apply(Output successOutput, LocalActivityFailedException exception);

  /** Serialized history output and an optional copied value for live execution. */
  final class Output {
    public final Optional<Payloads> payloads;
    public final @Nullable Object copiedResult;

    public Output(Optional<Payloads> payloads, @Nullable Object copiedResult) {
      this.payloads = payloads;
      this.copiedResult = copiedResult;
    }
  }

  class LocalActivityFailedException extends RuntimeException {
    private final @Nonnull Failure failure;
    private final int lastAttempt;

    /**
     * If this is not null, code that processes this exception will schedule a workflow timer to
     * continue retrying the execution
     */
    private final @Nullable Duration backoff;

    public LocalActivityFailedException(
        @Nonnull Failure failure,
        long originalScheduledTimestamp,
        int lastAttempt,
        @Nullable Duration backoff) {
      this.failure = failure;
      this.lastAttempt = lastAttempt;
      this.backoff = backoff;
    }

    @Nonnull
    public Failure getFailure() {
      return failure;
    }

    public int getLastAttempt() {
      return lastAttempt;
    }

    @Nullable
    public Duration getBackoff() {
      return backoff;
    }
  }
}
