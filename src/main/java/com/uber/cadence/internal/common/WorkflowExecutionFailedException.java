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

package com.uber.cadence.internal.common;

/** Framework level exception. Do not throw or catch in the application level code. */
public final class WorkflowExecutionFailedException extends RuntimeException {

  private final byte[] details;
  private final long decisionTaskCompletedEventId;

  WorkflowExecutionFailedException(
      String reason, byte[] details, long decisionTaskCompletedEventId) {
    super(reason);
    this.details = details;
    this.decisionTaskCompletedEventId = decisionTaskCompletedEventId;
  }

  public String getReason() {
    return getMessage();
  }

  public byte[] getDetails() {
    return details;
  }

  public long getDecisionTaskCompletedEventId() {
    return decisionTaskCompletedEventId;
  }
}
