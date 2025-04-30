/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.temporal.failure;

import com.google.common.base.Strings;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.EncodedValues;
import io.temporal.common.converter.Values;
import java.time.Duration;
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
 *   <li>category is set to {@link ApplicationErrorCategory#UNSPECIFIED}
 * </ul>
 */
public final class ApplicationFailure extends TemporalFailure {
  private final String type;
  private final Values details;
  private boolean nonRetryable;
  private Duration nextRetryDelay;
  private final ApplicationErrorCategory category;

  /** Creates a new builder for {@link ApplicationFailure}. */
  public static ApplicationFailure.Builder newBuilder() {
    return new ApplicationFailure.Builder();
  }

  /** Creates a new builder for {@link ApplicationFailure} initialized with the provided failure. */
  public static ApplicationFailure.Builder newBuilder(ApplicationFailure options) {
    return new ApplicationFailure.Builder(options);
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
    return new ApplicationFailure(
        message,
        type,
        false,
        new EncodedValues(details),
        cause,
        null,
        ApplicationErrorCategory.UNSPECIFIED);
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
   * @param nextRetryDelay delay before the next retry attempt.
   */
  public static ApplicationFailure newFailureWithCauseAndDelay(
      String message,
      String type,
      @Nullable Throwable cause,
      Duration nextRetryDelay,
      Object... details) {
    return new ApplicationFailure(
        message,
        type,
        false,
        new EncodedValues(details),
        cause,
        nextRetryDelay,
        ApplicationErrorCategory.UNSPECIFIED);
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
    return new ApplicationFailure(
        message,
        type,
        true,
        new EncodedValues(details),
        cause,
        null,
        ApplicationErrorCategory.UNSPECIFIED);
  }

  private ApplicationFailure(
      String message,
      String type,
      boolean nonRetryable,
      Values details,
      Throwable cause,
      Duration nextRetryDelay,
      ApplicationErrorCategory category) {
    super(getMessage(message, Objects.requireNonNull(type), nonRetryable), message, cause);
    this.type = type;
    this.details = details;
    this.nonRetryable = nonRetryable;
    this.nextRetryDelay = nextRetryDelay;
    this.category = category;
  }

  public String getType() {
    return type;
  }

  public Values getDetails() {
    return details;
  }

  @Nullable
  public Duration getNextRetryDelay() {
    return nextRetryDelay;
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

  public void setNextRetryDelay(Duration nextRetryDelay) {
    this.nextRetryDelay = nextRetryDelay;
  }

  public ApplicationErrorCategory getApplicationErrorCategory() {
    return category;
  }

  private static String getMessage(String message, String type, boolean nonRetryable) {
    return (Strings.isNullOrEmpty(message) ? "" : "message='" + message + "', ")
        + "type='"
        + type
        + '\''
        + ", nonRetryable="
        + nonRetryable;
  }

  public static final class Builder {
    private String message;
    private String type;
    private Values details;
    private boolean nonRetryable;
    private Throwable cause;
    private Duration nextRetryDelay;
    private ApplicationErrorCategory category;

    private Builder() {}

    private Builder(ApplicationFailure options) {
      if (options == null) {
        return;
      }
      this.message = options.getOriginalMessage();
      this.type = options.type;
      this.details = options.details;
      this.nonRetryable = options.nonRetryable;
      this.nextRetryDelay = options.nextRetryDelay;
      this.category = options.category;
    }

    /**
     * Sets the error type of this failure. This is used by {@link
     * io.temporal.common.RetryOptions.Builder#setDoNotRetry(String...)} to determine if the
     * exception is non retryable.
     */
    public Builder setType(String type) {
      this.type = type;
      return this;
    }


    /**
     * Set the optional error message.
     *
     * <p>Default is "".
     */
    public Builder setMessage(String message) {
      this.message = message;
      return this;
    }

    /**
     * Set the optional details of the failure.
     *
     * <p>Details are serialized using the same approach as arguments and results.
     */
    public Builder setDetails(Object... details) {
      this.details = new EncodedValues(details);
      return this;
    }

    /**
     * Set the optional details of the failure.
     *
     * <p>Details are serialized using the same approach as arguments and results.
     */
    public Builder setDetails(Values details) {
      this.details = details;
      return this;
    }

    /**
     * Set the non retryable flag on the failure.
     *
     * <p>It means that this exception is not going to be retried even if it is not included into
     * retry policy doNotRetry list.
     *
     * <p>Default is false.
     */
    public Builder setNonRetryable(boolean nonRetryable) {
      this.nonRetryable = nonRetryable;
      return this;
    }

    /**
     * Set the optional cause of the failure. Each element of the cause chain will be converted to
     * {@link ApplicationFailure} for network transmission across network if it doesn't extend
     * {@link TemporalFailure}.
     */
    public Builder setCause(Throwable cause) {
      this.cause = cause;
      return this;
    }

    /**
     * Set the optional delay before the next retry attempt. Overrides the normal retry delay.
     *
     * <p>Default is null.
     */
    public Builder setNextRetryDelay(Duration nextRetryDelay) {
      this.nextRetryDelay = nextRetryDelay;
      return this;
    }

    /**
     * Set the optional category of the failure.
     *
     * <p>Default is {@link ApplicationErrorCategory#UNSPECIFIED}.
     */
    public Builder setCategory(ApplicationErrorCategory category) {
      this.category = category;
      return this;
    }

    public ApplicationFailure build() {
      return new ApplicationFailure(
          message,
          type,
          nonRetryable,
          details == null ? new EncodedValues(null) : details,
          cause,
          nextRetryDelay,
          category);
    }
  }
}
