/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
      RetryableFunc<R, T> func, GrpcRetryerOptions options) throws T {
    return GrpcSyncRetryer.retry(serverCapabilities, func, options);
  }

  public <R> CompletableFuture<R> retryWithResultAsync(
      ScheduledExecutorService asyncThrottlerExecutor,
      Supplier<CompletableFuture<R>> function,
      GrpcRetryerOptions options) {
    return GrpcAsyncRetryer.retry(asyncThrottlerExecutor, serverCapabilities, function, options);
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
