/*
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

package com.uber.cadence.testing;

import com.google.common.annotations.VisibleForTesting;
import com.uber.cadence.TimeoutType;

/**
 * SimulatedTimeoutException can be thrown from an activity or child workflow implementation to
 * simulate a timeout. To be used only in unit tests. If thrown from an activity the workflow code
 * is going to receive it as {@link com.uber.cadence.workflow.ActivityTimeoutException}. If thrown
 * from a child workflow the workflow code is going to receive it as {@link
 * com.uber.cadence.workflow.ChildWorkflowTimedOutException}.
 */
@VisibleForTesting
public final class SimulatedTimeoutException extends RuntimeException {

  private final TimeoutType timeoutType;

  private final Object details;

  /**
   * Creates an instance with specific timeoutType and details. Use this constructor to simulate an
   * activity timeout.
   *
   * @param timeoutType timeout type to simulate
   * @param details details included into the timeout exception.
   */
  public SimulatedTimeoutException(TimeoutType timeoutType, Object details) {
    this.timeoutType = timeoutType;
    this.details = details;
  }

  /**
   * Creates an instance with no details and START_TO_CLOSE timeout. Use this constructor to
   * simulate a child workflow timeout.
   */
  public SimulatedTimeoutException() {
    this.timeoutType = TimeoutType.START_TO_CLOSE;
    this.details = null;
  }

  /**
   * Creates an instance with specific timeoutType and empty details. Use this constructor to
   * simulate an activity timeout.
   *
   * @param timeoutType timeout type to simulate
   */
  public SimulatedTimeoutException(TimeoutType timeoutType) {
    this.timeoutType = timeoutType;
    this.details = null;
  }

  public TimeoutType getTimeoutType() {
    return timeoutType;
  }

  public Object getDetails() {
    return details;
  }
}
