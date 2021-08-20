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

import io.temporal.serviceclient.RpcRetryOptions;
import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public final class GrpcRetryer {
  private static final GrpcSyncRetryer SYNC = new GrpcSyncRetryer(Clock.systemUTC());
  private static final GrpcAsyncRetryer ASYNC = new GrpcAsyncRetryer(Clock.systemUTC());

  public interface RetryableProc<E extends Throwable> {
    void apply() throws E;
  }

  public interface RetryableFunc<R, E extends Throwable> {
    R apply() throws E;
  }

  public static <T extends Throwable> void retry(RpcRetryOptions options, RetryableProc<T> r)
      throws T {
    retryWithResult(
        options,
        () -> {
          r.apply();
          return null;
        });
  }

  public static <R, T extends Throwable> R retryWithResult(
      RpcRetryOptions options, RetryableFunc<R, T> r) throws T {
    return SYNC.retry(options, r);
  }

  public static <R> CompletableFuture<R> retryWithResultAsync(
      RpcRetryOptions options, Supplier<CompletableFuture<R>> function) {
    return ASYNC.retry(options, function);
  }

  /** Prohibits instantiation. */
  private GrpcRetryer() {}
}
