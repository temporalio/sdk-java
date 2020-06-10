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

package io.temporal.failure;

import io.temporal.common.converter.Value;
import io.temporal.common.converter.WrappedValue;

public final class ApplicationException extends TemporalFailure {
  private final String type;
  private final Value details;
  private final boolean nonRetryable;

  public ApplicationException(
      String message, String type, Object details, boolean nonRetryable, Exception cause) {
    super(message, cause);
    this.type = type;
    this.details = new WrappedValue(details);
    this.nonRetryable = nonRetryable;
  }

  ApplicationException(
      String message, String type, Value details, boolean nonRetryable, Exception cause) {
    super(message, cause);
    this.type = type;
    this.details = details;
    this.nonRetryable = nonRetryable;
  }

  public String getType() {
    return type;
  }

  Value getDetails() {
    return details;
  }

  public boolean isNonRetryable() {
    return nonRetryable;
  }

  @Override
  public String toString() {
    return "ApplicationException{"
        + (getMessage() == null ? "" : "message='" + getMessage() + "\', ")
        + "type='"
        + type
        + '\''
        + ", nonRetryable="
        + nonRetryable
        + '}';
  }
}
