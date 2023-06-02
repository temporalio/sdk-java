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
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Description of a listed schedule. */
public final class ScheduleListDescription {

  public ScheduleListDescription(
      String scheduleId,
      ScheduleListSchedule schedule,
      ScheduleListInfo info,
      Map<String, Payload> memos,
      @Nonnull DataConverter dataConverter,
      Map<String, ?> searchAttributes) {
    this.scheduleId = scheduleId;
    this.schedule = schedule;
    this.info = info;
    this.memos = memos;
    this.dataConverter = dataConverter;
    this.searchAttributes = searchAttributes;
  }

  /**
   * Get the schedule IDs
   *
   * @return Schedule ID
   */
  public String getScheduleId() {
    return scheduleId;
  }

  /**
   * Gets the schedule.
   *
   * @return Schedule
   */
  public ScheduleListSchedule getSchedule() {
    return schedule;
  }

  /**
   * Get information about the schedule.
   *
   * @return Schedule info
   */
  public ScheduleListInfo getInfo() {
    return info;
  }

  @Nullable
  public <T> Object getMemo(String key, Class<T> valueClass) {
    return getMemo(key, valueClass, valueClass);
  }

  @Nullable
  public <T> T getMemo(String key, Class<T> valueClass, Type genericType) {
    Payload memo = memos.get(key);
    if (memo == null) {
      return null;
    }
    return dataConverter.fromPayload(memo, valueClass, genericType);
  }

  /**
   * Gets the search attributes on the schedule.
   *
   * @return Search attributes
   */
  public Map<String, ?> getSearchAttributes() {
    return searchAttributes;
  }

  private final String scheduleId;
  private final ScheduleListSchedule schedule;
  private final ScheduleListInfo info;
  private final Map<String, Payload> memos;
  private final @Nonnull DataConverter dataConverter;
  private final Map<String, ?> searchAttributes;
}
