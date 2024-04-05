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

package io.temporal.worker.tuning;

import io.temporal.activity.ActivityInfo;
import java.util.Objects;

/** Contains information about a slot that is being used to execute a local activity. */
public class LocalActivitySlotInfo {
  private final ActivityInfo activityInfo;
  private final String workerIdentity;
  private final String workerBuildId;

  public LocalActivitySlotInfo(ActivityInfo info, String workerIdentity, String workerBuildId) {
    this.activityInfo = info;
    this.workerIdentity = workerIdentity;
    this.workerBuildId = workerBuildId;
  }

  public ActivityInfo getActivityInfo() {
    return activityInfo;
  }

  public String getWorkerIdentity() {
    return workerIdentity;
  }

  public String getWorkerBuildId() {
    return workerBuildId;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    LocalActivitySlotInfo that = (LocalActivitySlotInfo) o;
    return Objects.equals(activityInfo, that.activityInfo)
        && Objects.equals(workerIdentity, that.workerIdentity)
        && Objects.equals(workerBuildId, that.workerBuildId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(activityInfo, workerIdentity, workerBuildId);
  }

  @Override
  public String toString() {
    return "LocalActivitySlotInfo{"
        + "activityInfo="
        + activityInfo
        + ", workerIdentity='"
        + workerIdentity
        + '\''
        + ", workerBuildId='"
        + workerBuildId
        + '\''
        + '}';
  }
}
