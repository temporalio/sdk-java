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

import com.google.common.base.Strings;
import io.temporal.common.converter.EncodedValue;
import io.temporal.common.converter.Value;

/**
 * Application failure is used to communicate application specific failures between workflows and
 * activities.
 *
 * <p>Throw this exception to have full control over type and details if the exception delivered to
 * the caller workflow or client.
 *
 * <p>Any unhandled exception which doesn't extend {@link TemporalFailure} is converted to an
 * instance of this class before being returned to a caller.
 *
 * <p>The {@code type} property is used by {@link io.temporal.common.RetryOptions} to determine if
 * an instance of this exception is non retryable. Another way to avoid retrying an exception of
 * this type is by setting {@code nonRetryable} flag to @{code true}.
 *
 * <p>The conversion of an exception that doesn't extend {@link TemporalFailure} to an
 * ApplicationFailure is done as following:
 *
 * <ul>
 *   <li>type is set to the exception full type name.
 *   <li>message is set to the exception message
 *   <li>nonRetryable is set to false
 *   <li>details are set to null
 *   <li>stack trace is copied from the original exception
 * </ul>
 */
public final class ApplicationFailure extends TemporalFailure {
  private final String type;
  private final Value details;
  private boolean nonRetryable;

  /**
   * @param message optional error message
   * @param type optional error type that is used by {@link
   *     io.temporal.common.RetryOptions#addDoNotRetry(String...)}.
   * @param details optional details about the failure. They are serialized using the same approach
   *     as arguments and results and can be accessed through {@link #getDetails()}
   * @param cause failure cause. Each element of the cause chain is converted to ApplicationFailure
   *     if it doesn't extend {@link TemporalFailure}.
   */
  public ApplicationFailure(String message, String type, Object details, Exception cause) {
    this(message, type, new EncodedValue(details), false, cause);
  }

  /**
   * @param message optional error message
   * @param type optional error type that is used by {@link
   *     io.temporal.common.RetryOptions#addDoNotRetry(String...)}.
   * @param details optional details about the failure. They are serialized using the same approach
   *     as arguments and results.
   */
  public ApplicationFailure(String message, String type, Object details) {
    this(message, type, new EncodedValue(details), false, null);
  }

  /**
   * @param message optional error message
   * @param type optional error type that is used by {@link
   *     io.temporal.common.RetryOptions#addDoNotRetry(String...)}.
   */
  public ApplicationFailure(String message, String type) {
    this(message, type, new EncodedValue(null), false, null);
  }

  /** * @param message optional error message */
  public ApplicationFailure(String message) {
    this(message, null);
  }

  ApplicationFailure(
      String message, String type, Value details, boolean nonRetryable, Exception cause) {
    super(getMessage(message, type, nonRetryable), message, cause);
    this.type = type;
    this.details = details;
    this.nonRetryable = nonRetryable;
  }

  public String getType() {
    return type;
  }

  public Value getDetails() {
    return details;
  }

  public void setNonRetryable(boolean nonRetryable) {
    this.nonRetryable = nonRetryable;
  }

  public boolean isNonRetryable() {
    return nonRetryable;
  }

  private static String getMessage(String message, String type, boolean nonRetryable) {
    return (Strings.isNullOrEmpty(message) ? "" : "message='" + message + "\', ")
        + "type='"
        + type
        + '\''
        + ", nonRetryable="
        + nonRetryable;
  }
}
