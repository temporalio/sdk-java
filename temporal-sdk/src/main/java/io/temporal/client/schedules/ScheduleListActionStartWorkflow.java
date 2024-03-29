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

import java.util.Objects;

/** Action to start a workflow on a listed schedule. */
public final class ScheduleListActionStartWorkflow extends ScheduleListAction {
  private final String workflow;

  public ScheduleListActionStartWorkflow(String workflow) {
    this.workflow = workflow;
  }

  /**
   * Get the scheduled workflow type name.
   *
   * @return Workflow type name
   */
  public String getWorkflow() {
    return workflow;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ScheduleListActionStartWorkflow that = (ScheduleListActionStartWorkflow) o;
    return Objects.equals(workflow, that.workflow);
  }

  @Override
  public int hashCode() {
    return Objects.hash(workflow);
  }

  @Override
  public String toString() {
    return "ScheduleListActionStartWorkflow{" + "workflow='" + workflow + '\'' + '}';
  }
}
