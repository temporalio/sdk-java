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
    private String summary;

    private Builder() {}

    private Builder(TimerOptions options) {
      if (options == null) {
        return;
      }
      this.summary = options.summary;
    }

    /**
     * Single-line fixed summary for this timer that will appear in UI/CLI. This can be in
     * single-line Temporal Markdown format.
     *
     * <p>Default is none/empty.
     */
    @Experimental
    public Builder setSummary(String summary) {
      this.summary = summary;
      return this;
    }

    public TimerOptions build() {
      return new TimerOptions(summary);
    }
  }

  private final String summary;

  private TimerOptions(String summary) {
    this.summary = summary;
  }

  public String getSummary() {
    return summary;
  }

  public Builder toBuilder() {
    return new Builder(this);
  }

  @Override
  public String toString() {
    return "TimerOptions{" + "summary='" + summary + '\'' + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    TimerOptions that = (TimerOptions) o;
    return Objects.equals(summary, that.summary);
  }

  @Override
  public int hashCode() {
    return Objects.hash(summary);
  }
}
