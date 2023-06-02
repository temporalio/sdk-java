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

package io.temporal.client.schedules;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Specification of the times scheduled actions may occur. The times are the union of {@link
 * ScheduleSpec#calendars}, {@link ScheduleSpec#intervals}, and {@link ScheduleSpec#cronExpressions}
 * excluding anything in {@link ScheduleSpec#skip}.
 */
public final class ScheduleSpec {
  public static ScheduleSpec.Builder newBuilder() {
    return new ScheduleSpec.Builder();
  }

  public static ScheduleSpec.Builder newBuilder(ScheduleSpec options) {
    return new ScheduleSpec.Builder(options);
  }

  public static ScheduleSpec getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final ScheduleSpec DEFAULT_INSTANCE;

  static {
    DEFAULT_INSTANCE = ScheduleSpec.newBuilder().build();
  }

  /**
   * Gets the calendar-based specification of times.
   *
   * @return list of {@link ScheduleCalendarSpec} the schedule may run at.
   */
  public List<ScheduleCalendarSpec> getCalendars() {
    return calendars;
  }

  /**
   * Gets the interval-based specification of times.
   *
   * @return list of {@link ScheduleIntervalSpec} the schedule may run at.
   */
  public List<ScheduleIntervalSpec> getIntervals() {
    return intervals;
  }

  /**
   * Gets the cron-based specification of times.
   *
   * <p>This is provided for easy migration from legacy string-based cron scheduling. New uses
   * should use {@link ScheduleSpec#calendars} instead. These expressions will be translated to
   * calendar-based specifications on the server.
   *
   * @return list of cron expressions the schedule may run at.
   */
  public List<String> getCronExpressions() {
    return cronExpressions;
  }

  /**
   * Gets the set of matching calendar times that will be skipped.
   *
   * @return list of {@link ScheduleCalendarSpec} the schedule will not run at.
   */
  public List<ScheduleCalendarSpec> getSkip() {
    return skip;
  }

  /**
   * Gets the time before which any matching times will be skipped.
   *
   * @return schedule start time
   */
  public Instant getStartAt() {
    return startAt;
  }

  /**
   * Gets the time after which any matching times will be skipped.
   *
   * @return schedule end time
   */
  public Instant getEndAt() {
    return endAt;
  }

  /**
   * Gets the jitter to apply to each action.
   *
   * @return amount of jitter applied
   */
  public Duration getJitter() {
    return jitter;
  }

  /**
   * Gets the IANA time zone name, for example <c>US/Central</c>.
   *
   * @return string representing the timezone.
   */
  public String getTimeZoneName() {
    return timeZoneName;
  }

  private final List<ScheduleCalendarSpec> calendars;
  private final List<ScheduleIntervalSpec> intervals;
  private final List<String> cronExpressions;
  private final List<ScheduleCalendarSpec> skip;
  private final Instant startAt;
  private final Instant endAt;
  private final Duration jitter;
  private final String timeZoneName;

  private ScheduleSpec(
      List<ScheduleCalendarSpec> calendars,
      List<ScheduleIntervalSpec> intervals,
      List<String> cronExpressions,
      List<ScheduleCalendarSpec> skip,
      Instant startAt,
      Instant endAt,
      Duration jitter,
      String timeZoneName) {
    this.calendars = calendars;
    this.intervals = intervals;
    this.cronExpressions = cronExpressions;
    this.skip = skip;
    this.startAt = startAt;
    this.endAt = endAt;
    this.jitter = jitter;
    this.timeZoneName = timeZoneName;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ScheduleSpec that = (ScheduleSpec) o;
    return Objects.equals(calendars, that.calendars)
        && Objects.equals(intervals, that.intervals)
        && Objects.equals(cronExpressions, that.cronExpressions)
        && Objects.equals(skip, that.skip)
        && Objects.equals(startAt, that.startAt)
        && Objects.equals(endAt, that.endAt)
        && Objects.equals(jitter, that.jitter)
        && Objects.equals(timeZoneName, that.timeZoneName);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        calendars, intervals, cronExpressions, skip, startAt, endAt, jitter, timeZoneName);
  }

  @Override
  public String toString() {
    return "ScheduleSpec{"
        + "calendars="
        + calendars
        + ", intervals="
        + intervals
        + ", cronExpressions="
        + cronExpressions
        + ", skip="
        + skip
        + ", startAt="
        + startAt
        + ", endAt="
        + endAt
        + ", jitter="
        + jitter
        + ", timeZoneName='"
        + timeZoneName
        + '\''
        + '}';
  }

  public static final class Builder {

    /** Set the calendar-based specification of times a schedule should run. */
    public Builder setCalendars(List<ScheduleCalendarSpec> calendars) {
      this.calendars = calendars;
      return this;
    }

    /** Set the interval-based specification of times a schedule should run. */
    public Builder setIntervals(List<ScheduleIntervalSpec> intervals) {
      this.intervals = intervals;
      return this;
    }

    /**
     * Set the cron-based specification of times a schedule should run.
     *
     * <p>This is provided for easy migration from legacy string-based cron scheduling. New uses
     * should use {@link ScheduleSpec#calendars} instead. These expressions will be translated to
     * calendar-based specifications on the server.
     */
    public Builder setCronExpressions(List<String> cronExpressions) {
      this.cronExpressions = cronExpressions;
      return this;
    }

    /** Set the calendar-based specification of times a schedule should not run. */
    public Builder setSkip(List<ScheduleCalendarSpec> skip) {
      this.skip = skip;
      return this;
    }

    /** Set the start time of the schedule, before which any matching times will be skipped. */
    public Builder setStartAt(Instant startAt) {
      this.startAt = startAt;
      return this;
    }

    /** Set the end time of the schedule, after which any matching times will be skipped. */
    public Builder setEndAt(Instant endAt) {
      this.endAt = endAt;
      return this;
    }

    /**
     * Set the jitter to apply to each action.
     *
     * <p>An action's schedule time will be incremented by a random value between 0 and this value
     * if present (but not past the next schedule).
     */
    public Builder setJitter(Duration jitter) {
      this.jitter = jitter;
      return this;
    }

    /** Set the schedules time zone as a string, for example <c>US/Central</c>. */
    public Builder setTimeZoneName(String timeZoneName) {
      this.timeZoneName = timeZoneName;
      return this;
    }

    private List<ScheduleCalendarSpec> calendars;
    private List<ScheduleIntervalSpec> intervals;
    private List<String> cronExpressions;
    private List<ScheduleCalendarSpec> skip;
    private Instant startAt;
    private Instant endAt;
    private Duration jitter;
    private String timeZoneName;

    private Builder() {}

    private Builder(ScheduleSpec options) {
      if (options == null) {
        return;
      }
      this.calendars = options.calendars;
      this.intervals = options.intervals;
      this.cronExpressions = options.cronExpressions;
      this.skip = options.skip;
      this.startAt = options.startAt;
      this.endAt = options.endAt;
      this.jitter = options.jitter;
      this.timeZoneName = options.timeZoneName;
    }

    public ScheduleSpec build() {
      return new ScheduleSpec(
          calendars, intervals, cronExpressions, skip, startAt, endAt, jitter, timeZoneName);
    }
  }
}
