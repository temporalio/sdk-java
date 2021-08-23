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
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.EncodedValues;
import io.temporal.common.converter.Values;
import java.util.Objects;
import javax.annotation.Nullable;

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
  private final Values details;
  private boolean nonRetryable;

  /**
   * New ApplicationFailure with {@link #isNonRetryable()} flag set to false.
   *
   * <p>Note that this exception still may not be retried by the service if its type is included in
   * the doNotRetry property of the correspondent retry policy.
   *
   * @param message optional error message
   * @param type optional error type that is used by {@link
   *     io.temporal.common.RetryOptions.Builder#setDoNotRetry(String...)}.
   * @param details optional details about the failure. They are serialized using the same approach
   *     as arguments and results.
   */
  public static ApplicationFailure newFailure(String message, String type, Object... details) {
    return newFailureWithCause(message, type, null, details);
  }

  /**
   * New ApplicationFailure with {@link #isNonRetryable()} flag set to false.
   *
   * <p>Note that this exception still may not be retried by the service if its type is included in
   * the doNotRetry property of the correspondent retry policy.
   *
   * @param message optional error message
   * @param type optional error type that is used by {@link
   *     io.temporal.common.RetryOptions.Builder#setDoNotRetry(String...)}.
   * @param details optional details about the failure. They are serialized using the same approach
   *     as arguments and results.
   * @param cause failure cause. Each element of the cause chain will be converted to
   *     ApplicationFailure for network transmission across network if it doesn't extend {@link
   *     TemporalFailure}
   */
  public static ApplicationFailure newFailureWithCause(
      String message, String type, @Nullable Throwable cause, Object... details) {
    return new ApplicationFailure(message, type, false, new EncodedValues(details), cause);
  }

  /**
   * New ApplicationFailure with {@link #isNonRetryable()} flag set to true.
   *
   * <p>It means that this exception is not going to be retried even if it is not included into
   * retry policy doNotRetry list.
   *
   * @param message optional error message
   * @param type error type
   * @param details optional details about the failure. They are serialized using the same approach
   *     as arguments and results.
   */
  public static ApplicationFailure newNonRetryableFailure(
      String message, String type, Object... details) {
    return newNonRetryableFailureWithCause(message, type, null, details);
  }

  /**
   * New ApplicationFailure with {@link #isNonRetryable()} flag set to true.
   *
   * <p>This exception will not be retried even if it is absent from the retry policy doNotRetry
   * list.
   *
   * @param message optional error message
   * @param type error type
   * @param details optional details about the failure. They are serialized using the same approach
   *     as arguments and results.
   * @param cause failure cause. Each element of the cause chain will be converted to
   *     ApplicationFailure for network transmission across network if it doesn't extend {@link
   *     TemporalFailure}
   */
  public static ApplicationFailure newNonRetryableFailureWithCause(
      String message, String type, @Nullable Throwable cause, Object... details) {
    return new ApplicationFailure(message, type, true, new EncodedValues(details), cause);
  }

  static ApplicationFailure newFromValues(
      String message, String type, boolean nonRetryable, Values details, Throwable cause) {
    return new ApplicationFailure(message, type, nonRetryable, details, cause);
  }

  ApplicationFailure(
      String message, String type, boolean nonRetryable, Values details, Throwable cause) {
    super(getMessage(message, Objects.requireNonNull(type), nonRetryable), message, cause);
    this.type = type;
    this.details = details;
    this.nonRetryable = nonRetryable;
  }

  public String getType() {
    return type;
  }

  public Values getDetails() {
    return details;
  }

  public void setNonRetryable(boolean nonRetryable) {
    this.nonRetryable = nonRetryable;
  }

  public boolean isNonRetryable() {
    return nonRetryable;
  }

  @Override
  public void setDataConverter(DataConverter converter) {
    ((EncodedValues) details).setDataConverter(converter);
  }

  private static String getMessage(String message, String type, boolean nonRetryable) {
    return (Strings.isNullOrEmpty(message) ? "" : "message='" + message + "', ")
        + "type='"
        + type
        + '\''
        + ", nonRetryable="
        + nonRetryable;
  }
}
