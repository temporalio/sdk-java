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

import io.temporal.api.common.v1.Payload;
import io.temporal.common.converter.DataConverter;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Description of a schedule. */
public final class ScheduleDescription {
  private final String id;
  private final ScheduleInfo info;
  private final Schedule schedule;
  private final Map<String, List<?>> searchAttributes;
  private final Map<String, Payload> memo;
  private final DataConverter dataConverter;

  public ScheduleDescription(
      String id,
      ScheduleInfo info,
      Schedule schedule,
      Map<String, List<?>> searchAttributes,
      Map<String, Payload> memo,
      DataConverter dataConverter) {
    this.id = id;
    this.info = info;
    this.schedule = schedule;
    this.searchAttributes = searchAttributes;
    this.memo = memo;
    this.dataConverter = dataConverter;
  }

  /**
   * Get the ID of the schedule.
   *
   * @return schedule ID
   */
  public @Nonnull String getId() {
    return id;
  }

  /**
   * Get information about the schedule.
   *
   * @return schedule info
   */
  public @Nonnull ScheduleInfo getInfo() {
    return info;
  }

  /**
   * Gets the schedule details.
   *
   * @return schedule details
   */
  public @Nonnull Schedule getSchedule() {
    return schedule;
  }

  /**
   * Gets the search attributes on the schedule.
   *
   * @return search attributes
   */
  @Nonnull
  public Map<String, List<?>> getSearchAttributes() {
    return searchAttributes;
  }

  @Nullable
  public <T> Object getMemo(String key, Class<T> valueClass) {
    return getMemo(key, valueClass, valueClass);
  }

  @Nullable
  public <T> T getMemo(String key, Class<T> valueClass, Type genericType) {
    Payload memoPayload = this.memo.get(key);
    if (memo == null) {
      return null;
    }
    return dataConverter.fromPayload(memoPayload, valueClass, genericType);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ScheduleDescription that = (ScheduleDescription) o;
    return Objects.equals(id, that.id)
        && Objects.equals(info, that.info)
        && Objects.equals(schedule, that.schedule)
        && Objects.equals(searchAttributes, that.searchAttributes)
        && Objects.equals(memo, that.memo);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, info, schedule, searchAttributes, memo);
  }

  @Override
  public String toString() {
    return "ScheduleDescription{"
        + "id='"
        + id
        + '\''
        + ", info="
        + info
        + ", schedule="
        + schedule
        + ", searchAttributes="
        + searchAttributes
        + ", memo="
        + memo
        + '}';
  }
}
