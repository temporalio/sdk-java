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

import static io.grpc.Status.Code.DEADLINE_EXCEEDED;

import io.grpc.Deadline;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.serviceclient.RpcRetryOptions;
import io.temporal.serviceclient.StatusUtils;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

class GrpcRetryerUtils {
  /**
   * This method encapsulates the logic if {@code StatusRuntimeException exception} is retryable or
   * not.
   *
   * @param currentException exception to analyze
   * @param previousException previous exception happened before this one, {@code null} if {@code
   *     currentException} is the first exception in the chain
   * @param options retry options
   * @param grpcContextDeadline current grpc context deadline
   * @return null if the {@code exception} can be retried, a final exception to throw in the
   *     external code otherwise.
   */
  static @Nullable RuntimeException createFinalExceptionIfNotRetryable(
      @Nonnull StatusRuntimeException currentException,
      @Nullable StatusRuntimeException previousException,
      @Nonnull RpcRetryOptions options,
      @Nullable Deadline grpcContextDeadline) {
    Status.Code code = currentException.getStatus().getCode();

    switch (code) {
        // CANCELLED and DEADLINE_EXCEEDED usually considered non-retryable in GRPC world, for
        // example:
        // https://github.com/grpc-ecosystem/go-grpc-middleware/blob/master/retry/retry.go#L287
      case CANCELLED:
        return new CancellationException();
      case DEADLINE_EXCEEDED:
        if (grpcContextDeadline != null && grpcContextDeadline.isExpired()) {
          return lastMeaningfulException(currentException, previousException);
        } else {
          // If this specific request's deadline has expired, but the higher-level deadline
          // that was established when GrpcRetryer was initialized not been exceeded, we
          // should keep retrying.
          break;
        }
      default:
        for (RpcRetryOptions.DoNotRetryItem pair : options.getDoNotRetry()) {
          if (pair.getCode() == code
              && (pair.getDetailsClass() == null
                  || StatusUtils.hasFailure(currentException, pair.getDetailsClass()))) {
            return currentException;
          }
        }
    }

    return null;
  }

  static StatusRuntimeException lastMeaningfulException(
      @Nonnull StatusRuntimeException currentException,
      @Nullable StatusRuntimeException previousException) {
    if (currentException.getStatus().getCode() == DEADLINE_EXCEEDED) {
      if (previousException != null
          && previousException.getStatus().getCode() != DEADLINE_EXCEEDED) {
        // If there was another exception before this DEADLINE_EXCEEDED that wasn't
        // DEADLINE_EXCEEDED, we prefer it
        // over DEADLINE_EXCEEDED
        return previousException;
      } else {
        return currentException;
      }
    } else {
      return currentException;
    }
  }

  /**
   * @param options retry options
   * @param startTimeMs timestamp when attempts started
   * @param currentTimeMillis current timestamp
   * @param attempt number of the last made attempt
   * @return true if we out of attempts or time to retry
   */
  static boolean ranOutOfRetries(
      RpcRetryOptions options,
      long startTimeMs,
      long currentTimeMillis,
      int attempt,
      @Nullable Deadline grpcContextDeadline) {
    int maxAttempts = options.getMaximumAttempts();
    Duration expirationDuration = options.getExpiration();
    long expirationInterval = expirationDuration != null ? expirationDuration.toMillis() : 0;

    return (maxAttempts > 0 && attempt >= maxAttempts)
        || (expirationDuration != null && currentTimeMillis - startTimeMs >= expirationInterval)
        || (grpcContextDeadline != null && grpcContextDeadline.isExpired());
  }
}
