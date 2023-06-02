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

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Specification relative to calendar time when to run an action.
 *
 * <p>A timestamp matches if at least one range of each field matches except for year. If year is
 * missing, that means all years match. For all fields besides year, at least one range must be
 * present to match anything.
 */
public final class ScheduleCalendarSpec {
  public static ScheduleCalendarSpec.Builder newBuilder() {
    return new ScheduleCalendarSpec.Builder();
  }

  public static ScheduleCalendarSpec.Builder newBuilder(ScheduleCalendarSpec spec) {
    return new ScheduleCalendarSpec.Builder(spec);
  }

  public static ScheduleCalendarSpec getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final ScheduleCalendarSpec DEFAULT_INSTANCE;

  static {
    DEFAULT_INSTANCE = ScheduleCalendarSpec.newBuilder().build();
  }

  public static final List<ScheduleRange> BEGINNING =
      Collections.singletonList(new ScheduleRange(0));
  public static final List<ScheduleRange> ALL_MONTH_DAYS =
      Collections.singletonList(new ScheduleRange(1, 31));
  public static final List<ScheduleRange> ALL_MONTHS =
      Collections.singletonList(new ScheduleRange(1, 12));
  public static final List<ScheduleRange> ALL_WEEK_DAYS =
      Collections.singletonList(new ScheduleRange(0, 6));

  public List<ScheduleRange> getSeconds() {
    return seconds;
  }

  public List<ScheduleRange> getMinutes() {
    return minutes;
  }

  public List<ScheduleRange> getHour() {
    return hour;
  }

  public List<ScheduleRange> getDayOfMonth() {
    return dayOfMonth;
  }

  public List<ScheduleRange> getMonth() {
    return month;
  }

  public List<ScheduleRange> getYear() {
    return year;
  }

  public List<ScheduleRange> getDayOfWeek() {
    return dayOfWeek;
  }

  public String getComment() {
    return comment;
  }

  private final List<ScheduleRange> seconds;
  private final List<ScheduleRange> minutes;
  private final List<ScheduleRange> hour;
  private final List<ScheduleRange> dayOfMonth;
  private final List<ScheduleRange> month;
  private final List<ScheduleRange> year;
  private final List<ScheduleRange> dayOfWeek;
  private final String comment;

  private ScheduleCalendarSpec(
      List<ScheduleRange> seconds,
      List<ScheduleRange> minutes,
      List<ScheduleRange> hour,
      List<ScheduleRange> dayOfMonth,
      List<ScheduleRange> month,
      List<ScheduleRange> year,
      List<ScheduleRange> dayOfWeek,
      String comment) {
    this.seconds = seconds;
    this.minutes = minutes;
    this.hour = hour;
    this.dayOfMonth = dayOfMonth;
    this.month = month;
    this.year = year;
    this.dayOfWeek = dayOfWeek;
    this.comment = comment;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ScheduleCalendarSpec that = (ScheduleCalendarSpec) o;
    return Objects.equals(seconds, that.seconds)
        && Objects.equals(minutes, that.minutes)
        && Objects.equals(hour, that.hour)
        && Objects.equals(dayOfMonth, that.dayOfMonth)
        && Objects.equals(month, that.month)
        && Objects.equals(year, that.year)
        && Objects.equals(dayOfWeek, that.dayOfWeek)
        && Objects.equals(comment, that.comment);
  }

  @Override
  public int hashCode() {
    return Objects.hash(seconds, minutes, hour, dayOfMonth, month, year, dayOfWeek, comment);
  }

  @Override
  public String toString() {
    return "ScheduleCalendarSpec{"
        + "seconds="
        + seconds
        + ", minutes="
        + minutes
        + ", hour="
        + hour
        + ", dayOfMonth="
        + dayOfMonth
        + ", month="
        + month
        + ", year="
        + year
        + ", dayOfWeek="
        + dayOfWeek
        + ", comment='"
        + comment
        + '\''
        + '}';
  }

  public static class Builder {
    public Builder setSeconds(List<ScheduleRange> seconds) {
      this.seconds = seconds;
      return this;
    }

    public Builder setMinutes(List<ScheduleRange> minutes) {
      this.minutes = minutes;
      return this;
    }

    public Builder setHour(List<ScheduleRange> hour) {
      this.hour = hour;
      return this;
    }

    public Builder setDayOfMonth(List<ScheduleRange> dayOfMonth) {
      this.dayOfMonth = dayOfMonth;
      return this;
    }

    public Builder setMonth(List<ScheduleRange> month) {
      this.month = month;
      return this;
    }

    public Builder setYear(List<ScheduleRange> year) {
      this.year = year;
      return this;
    }

    public Builder setDayOfWeek(List<ScheduleRange> dayOfWeek) {
      this.dayOfWeek = dayOfWeek;
      return this;
    }

    public Builder setComment(String comment) {
      this.comment = comment;
      return this;
    }

    private List<ScheduleRange> seconds;
    private List<ScheduleRange> minutes;
    private List<ScheduleRange> hour;
    private List<ScheduleRange> dayOfMonth;
    private List<ScheduleRange> month;
    private List<ScheduleRange> year;
    private List<ScheduleRange> dayOfWeek;
    private String comment;

    private Builder() {}

    private Builder(ScheduleCalendarSpec spec) {
      if (spec == null) {
        return;
      }
      this.seconds = spec.seconds;
      this.minutes = spec.minutes;
      this.hour = spec.hour;
      this.dayOfMonth = spec.dayOfMonth;
      this.month = spec.month;
      this.year = spec.year;
      this.dayOfWeek = spec.dayOfWeek;
      this.comment = spec.comment;
    }

    public ScheduleCalendarSpec build() {
      return new ScheduleCalendarSpec(
          seconds == null ? ScheduleCalendarSpec.BEGINNING : seconds,
          minutes == null ? ScheduleCalendarSpec.BEGINNING : minutes,
          hour == null ? ScheduleCalendarSpec.BEGINNING : hour,
          dayOfMonth == null ? ScheduleCalendarSpec.ALL_MONTH_DAYS : dayOfMonth,
          month == null ? ScheduleCalendarSpec.ALL_MONTHS : month,
          year == null ? Collections.EMPTY_LIST : year,
          dayOfWeek == null ? ScheduleCalendarSpec.ALL_WEEK_DAYS : dayOfWeek,
          comment == null ? "" : comment);
    }
  }
}
