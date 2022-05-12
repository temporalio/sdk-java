/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package io.temporal.internal.retryer;

import io.grpc.Deadline;
import io.temporal.serviceclient.RpcRetryOptions;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import javax.annotation.Nullable;

public final class GrpcRetryer {
  private static final GrpcSyncRetryer SYNC = new GrpcSyncRetryer();
  private static final GrpcAsyncRetryer ASYNC = new GrpcAsyncRetryer();

  public interface RetryableProc<E extends Throwable> {
    void apply() throws E;
  }

  public interface RetryableFunc<R, E extends Throwable> {
    R apply() throws E;
  }

  public static <T extends Throwable> void retry(RpcRetryOptions options, RetryableProc<T> r)
      throws T {
    retry(options, r, null);
  }

  /**
   * @param options allows partially built options without an expiration without an expiration or
   *     maxAttempts set if {@code retriesDeadline} is supplied
   */
  public static <T extends Throwable> void retry(
      RpcRetryOptions options, RetryableProc<T> r, @Nullable Deadline retriesDeadline) throws T {
    retryWithResult(
        options,
        () -> {
          r.apply();
          return null;
        },
        retriesDeadline);
  }

  public static <R, T extends Throwable> R retryWithResult(
      RpcRetryOptions options, RetryableFunc<R, T> r) throws T {
    return retryWithResult(options, r, null);
  }

  /**
   * @param options allows partially built options without an expiration without an expiration or
   *     maxAttempts set if {@code retriesDeadline} is supplied
   */
  public static <R, T extends Throwable> R retryWithResult(
      RpcRetryOptions options, RetryableFunc<R, T> r, @Nullable Deadline deadline) throws T {
    return SYNC.retry(options, r, deadline);
  }

  public static <R> CompletableFuture<R> retryWithResultAsync(
      RpcRetryOptions options, Supplier<CompletableFuture<R>> function) {
    return ASYNC.retry(options, function, null);
  }

  /**
   * @param options allows partially built options without an expiration without an expiration or
   *     maxAttempts set if {@code retriesDeadline} is supplied
   */
  public static <R> CompletableFuture<R> retryWithResultAsync(
      RpcRetryOptions options,
      Supplier<CompletableFuture<R>> function,
      @Nullable Deadline deadline) {
    return ASYNC.retry(options, function, deadline);
  }

  /** Prohibits instantiation. */
  private GrpcRetryer() {}
}
