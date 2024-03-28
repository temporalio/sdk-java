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

import io.grpc.*;
import io.temporal.api.workflowservice.v1.GetSystemInfoResponse.Capabilities;
import io.temporal.internal.BackoffThrottler;
import io.temporal.internal.retryer.GrpcRetryer.GrpcRetryerOptions;
import io.temporal.internal.retryer.GrpcRetryer.RetryableFunc;
import io.temporal.serviceclient.RpcRetryOptions;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class GrpcSyncRetryer {
  private static final Logger log = LoggerFactory.getLogger(GrpcRetryer.class);

  private GrpcSyncRetryer() {}

  public static <R, T extends Throwable> R retry(
      Supplier<Capabilities> serverCapabilities,
      RetryableFunc<R, T> func,
      GrpcRetryerOptions options)
      throws T {
    options.validate();
    RpcRetryOptions rpcOptions = options.getOptions();
    @Nullable Deadline deadline = options.getDeadline();
    @Nullable
    Deadline retriesExpirationDeadline =
        GrpcRetryerUtils.mergeDurationWithAnAbsoluteDeadline(rpcOptions.getExpiration(), deadline);
    BackoffThrottler throttler =
        new BackoffThrottler(
            rpcOptions.getInitialInterval(),
            rpcOptions.getCongestionInitialInterval(),
            rpcOptions.getMaximumInterval(),
            rpcOptions.getBackoffCoefficient(),
            rpcOptions.getMaximumJitterCoefficient());

    int attempt = 0;
    StatusRuntimeException lastMeaningfulException = null;
    do {
      attempt++;

      try {
        long throttleMs = throttler.getSleepTime();
        if (throttleMs > 0) {
          Thread.sleep(throttleMs);
        }
        if (lastMeaningfulException != null) {
          log.debug("Retrying after failure", lastMeaningfulException);
        }
        R result = func.apply();
        throttler.success();
        return result;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new CancellationException();
      } catch (StatusRuntimeException e) {
        RuntimeException finalException =
            GrpcRetryerUtils.createFinalExceptionIfNotRetryable(e, rpcOptions, serverCapabilities);
        if (finalException != null) {
          log.debug("Final exception, throwing", finalException);
          throw finalException;
        }
        lastMeaningfulException =
            GrpcRetryerUtils.lastMeaningfulException(e, lastMeaningfulException);
        throttler.failure(e.getStatus().getCode());
      }
      // No catch block for any other exceptions because we don't retry them, we pass them through.
      // It's designed this way because it's GrpcRetryer, not general purpose retryer.
    } while (!GrpcRetryerUtils.ranOutOfRetries(
        rpcOptions, attempt, retriesExpirationDeadline, Context.current().getDeadline()));

    log.debug("Out of retries, throwing", lastMeaningfulException);
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
