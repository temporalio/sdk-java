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

import io.grpc.Context;
import io.grpc.Deadline;
import io.grpc.StatusRuntimeException;
import io.temporal.internal.BackoffThrottler;
import io.temporal.serviceclient.RpcRetryOptions;
import java.util.concurrent.CancellationException;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class GrpcSyncRetryer {
  private static final Logger log = LoggerFactory.getLogger(GrpcSyncRetryer.class);

  public <R, T extends Throwable> R retry(
      GrpcRetryer.RetryableFunc<R, T> r, GrpcRetryer.GrpcRetryerOptions options) throws T {
    options.validate();
    RpcRetryOptions rpcOptions = options.getOptions();
    @Nullable Deadline deadline = options.getDeadline();
    @Nullable
    Deadline retriesExpirationDeadline =
        GrpcRetryerUtils.mergeDurationWithAnAbsoluteDeadline(rpcOptions.getExpiration(), deadline);
    BackoffThrottler throttler =
        new BackoffThrottler(
            rpcOptions.getInitialInterval(),
            rpcOptions.getMaximumInterval(),
            rpcOptions.getBackoffCoefficient());

    int attempt = 0;
    StatusRuntimeException lastMeaningfulException = null;
    do {
      attempt++;
      if (lastMeaningfulException != null) {
        log.info("Retrying after failure", lastMeaningfulException);
      }

      try {
        throttler.throttle();
        R result = r.apply();
        throttler.success();
        return result;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new CancellationException();
      } catch (StatusRuntimeException e) {
        RuntimeException finalException =
            GrpcRetryerUtils.createFinalExceptionIfNotRetryable(e, rpcOptions);
        if (finalException != null) {
          log.warn("Non retryable failure", finalException);
          throw finalException;
        }
        lastMeaningfulException =
            GrpcRetryerUtils.lastMeaningfulException(e, lastMeaningfulException);
      }
      // No catch block for any other exceptions because we don't retry them, we pass them through.
      // It's designed this way because it's GrpcRetryer, not general purpose retryer.

      throttler.failure();
    } while (!GrpcRetryerUtils.ranOutOfRetries(
        rpcOptions, attempt, retriesExpirationDeadline, Context.current().getDeadline()));

    log.warn("Failure, out of retries", lastMeaningfulException);
    rethrow(lastMeaningfulException);
    throw new IllegalStateException("unreachable");
  }

  private static <T extends Throwable> void rethrow(Exception e) throws T {
    if (e instanceof RuntimeException) {
      throw (RuntimeException) e;
    } else {
      @SuppressWarnings("unchecked")
      T toRethrow = (T) e;
      throw toRethrow;
    }
  }
}
