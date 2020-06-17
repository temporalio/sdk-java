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

package io.temporal.internal.common;

import io.temporal.enums.v1.RetryStatus;
import io.temporal.failure.v1.Failure;

/** Framework level exception. Do not throw or catch in the application level code. */
public final class WorkflowExecutionFailedException extends RuntimeException {

  private final long decisionTaskCompletedEventId;
  private final Failure failure;
  private final RetryStatus retryStatus;

  WorkflowExecutionFailedException(
      Failure failure, long decisionTaskCompletedEventId, RetryStatus retryStatus) {
    this.failure = failure;
    this.decisionTaskCompletedEventId = decisionTaskCompletedEventId;
    this.retryStatus = retryStatus;
  }

  public Failure getFailure() {
    return failure;
  }

  public long getDecisionTaskCompletedEventId() {
    return decisionTaskCompletedEventId;
  }

  public RetryStatus getRetryStatus() {
    return retryStatus;
  }
}
