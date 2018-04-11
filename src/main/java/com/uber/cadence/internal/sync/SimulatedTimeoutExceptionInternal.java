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

package com.uber.cadence.internal.sync;

import com.uber.cadence.TimeoutType;

/**
 * SimulatedTimeoutExceptionInternal is created from a SimulatedTimeoutException. The main
 * difference is that the details are in a serialized form.
 */
final class SimulatedTimeoutExceptionInternal extends RuntimeException {

  private final TimeoutType timeoutType;

  private final byte[] details;

  SimulatedTimeoutExceptionInternal(TimeoutType timeoutType, byte[] details) {
    this.timeoutType = timeoutType;
    this.details = details;
  }

  SimulatedTimeoutExceptionInternal(TimeoutType timeoutType) {
    this.timeoutType = timeoutType;
    this.details = null;
  }

  TimeoutType getTimeoutType() {
    return timeoutType;
  }

  byte[] getDetails() {
    return details;
  }
}
