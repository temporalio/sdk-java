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
          // If our higher level GRPC context deadline is expired,
          // the underlying DEADLINE_EXCEEDED is likely meaningless, and
          // we try to preserve the previous exception if it's present
          return previousException != null ? previousException : currentException;
        } else {
          // If the deadline of the specific request has been setup on a lower level than
          // GrpcRetryer and it's
          // fired, but we are not run out of GrpcRetryer attempts, GrpcRetryer timeout or
          // GrpcContext deadline,
          // we should retry.
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
