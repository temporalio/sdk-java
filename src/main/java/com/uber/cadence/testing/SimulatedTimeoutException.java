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

import com.uber.cadence.TimeoutType;

/**
 * SimulatedTimeoutException can be thrown from an activity or child workflow implementation to
 * simulate a timeout. To be used only in unit tests. If thrown from an activity the workflow code
 * is going to receive it as {@link com.uber.cadence.workflow.ActivityTimeoutException}. If thrown
 * from a child workflow the workflow code is going to receive it as {@link
 * com.uber.cadence.workflow.ChildWorkflowTimedOutException}.
 */
public final class SimulatedTimeoutException extends RuntimeException {

  private final TimeoutType timeoutType;

  private final Object details;

  public SimulatedTimeoutException(TimeoutType timeoutType, Object details) {
    this.timeoutType = timeoutType;
    this.details = details;
  }

  public SimulatedTimeoutException() {
    this.timeoutType = TimeoutType.START_TO_CLOSE;
    this.details = null;
  }

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
