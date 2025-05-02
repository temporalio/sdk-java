package io.temporal.internal.retryer;

import com.google.common.base.Preconditions;
import io.grpc.Deadline;
import io.temporal.api.workflowservice.v1.GetSystemInfoResponse;
import io.temporal.serviceclient.RpcRetryOptions;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class GrpcRetryer {

  private final Supplier<GetSystemInfoResponse.Capabilities> serverCapabilities;

  public interface RetryableProc<E extends Throwable> {
    void apply() throws E;
  }

  public interface RetryableFunc<R, E extends Throwable> {
    R apply() throws E;
  }

  public GrpcRetryer(Supplier<GetSystemInfoResponse.Capabilities> serverCapabilities) {
    this.serverCapabilities = serverCapabilities;
  }

  public <T extends Throwable> void retry(RetryableProc<T> r, GrpcRetryerOptions options) throws T {
    retryWithResult(
        () -> {
          r.apply();
          return null;
        },
        options);
  }

  public <R, T extends Throwable> R retryWithResult(
      RetryableFunc<R, T> r, GrpcRetryerOptions options) throws T {
    return new GrpcSyncRetryer().retry(r, options, serverCapabilities.get());
  }

  public <R> CompletableFuture<R> retryWithResultAsync(
      ScheduledExecutorService asyncThrottlerExecutor,
      Supplier<CompletableFuture<R>> function,
      GrpcRetryerOptions options) {
    return new GrpcAsyncRetryer<>(
            asyncThrottlerExecutor, function, options, serverCapabilities.get())
        .retry();
  }

  public static class GrpcRetryerOptions {
    @Nonnull private final RpcRetryOptions options;
    @Nullable private final Deadline deadline;

    /**
     * @param options allows partially built options without an expiration without an expiration or
     *     * maxAttempts set if {@code retriesDeadline} is supplied
     * @param deadline an absolute deadline for the retries
     */
    public GrpcRetryerOptions(@Nonnull RpcRetryOptions options, @Nullable Deadline deadline) {
      this.options = options;
      this.deadline = deadline;
    }

    @Nonnull
    public RpcRetryOptions getOptions() {
      return options;
    }

    @Nullable
    public Deadline getDeadline() {
      return deadline;
    }

    public void validate() {
      options.validate(false);
      Preconditions.checkState(
          options.getMaximumInterval() != null
              || options.getMaximumAttempts() > 0
              || deadline != null,
          "configuration of the retries has to be finite");
    }
  }
}
