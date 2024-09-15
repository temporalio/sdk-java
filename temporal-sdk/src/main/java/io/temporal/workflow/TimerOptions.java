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

package io.temporal.workflow;

import io.temporal.common.Experimental;
import java.util.Objects;

/** TimerOptions is used to specify options for a timer. */
public final class TimerOptions {

  public static TimerOptions.Builder newBuilder() {
    return new TimerOptions.Builder();
  }

  public static TimerOptions.Builder newBuilder(TimerOptions options) {
    return new TimerOptions.Builder(options);
  }

  public static TimerOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final TimerOptions DEFAULT_INSTANCE;

  static {
    DEFAULT_INSTANCE = TimerOptions.newBuilder().build();
  }

  public static final class Builder {
    private String staticSummary;
    private String staticDetails;

    private Builder() {}

    private Builder(TimerOptions options) {
      if (options == null) {
        return;
      }
      this.staticSummary = options.staticSummary;
      this.staticDetails = options.staticDetails;
    }

    /**
     * Single-line fixed summary for this timer that will appear in UI/CLI. This can be in
     * single-line Temporal Markdown format.
     *
     * <p>Default is none/empty.
     */
    @Experimental
    public Builder setStaticSummary(String staticSummary) {
      this.staticSummary = staticSummary;
      return this;
    }

    /**
     * General fixed details for this timer that will appear in UI/CLI. This can be in Temporal
     * Markdown format and can span multiple lines. This is a fixed value on the workflow that
     * cannot be updated.
     *
     * <p>Default is none/empty.
     */
    @Experimental
    public Builder setStaticDetails(String staticDetails) {
      this.staticDetails = staticDetails;
      return this;
    }

    public TimerOptions build() {
      return new TimerOptions(staticSummary, staticDetails);
    }
  }

  private final String staticSummary;
  private final String staticDetails;

  public TimerOptions(String staticSummary, String staticDetails) {
    this.staticSummary = staticSummary;
    this.staticDetails = staticDetails;
  }

  public String getStaticSummary() {
    return staticSummary;
  }

  public String getStaticDetails() {
    return staticDetails;
  }

  public Builder toBuilder() {
    return new Builder(this);
  }

  @Override
  public String toString() {
    return "TimerOptions{"
        + "staticSummary='"
        + staticSummary
        + '\''
        + ", staticDetails='"
        + staticDetails
        + '\''
        + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    TimerOptions that = (TimerOptions) o;
    return Objects.equals(staticSummary, that.staticSummary)
        && Objects.equals(staticDetails, that.staticDetails);
  }

  @Override
  public int hashCode() {
    return Objects.hash(staticSummary, staticDetails);
  }
}
