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

import java.util.Objects;
import javax.annotation.Nonnull;

public class TunerHolder implements WorkerTuner {
  private final @Nonnull SlotSupplier<WorkflowSlotInfo> workflowTaskSlotSupplier;
  private final @Nonnull SlotSupplier<ActivitySlotInfo> activityTaskSlotSupplier;
  private final @Nonnull SlotSupplier<LocalActivitySlotInfo> localActivitySlotSupplier;

  public TunerHolder(
      @Nonnull SlotSupplier<WorkflowSlotInfo> workflowTaskSlotSupplier,
      @Nonnull SlotSupplier<ActivitySlotInfo> activityTaskSlotSupplier,
      @Nonnull SlotSupplier<LocalActivitySlotInfo> localActivitySlotSupplier) {
    this.workflowTaskSlotSupplier = Objects.requireNonNull(workflowTaskSlotSupplier);
    this.activityTaskSlotSupplier = Objects.requireNonNull(activityTaskSlotSupplier);
    this.localActivitySlotSupplier = Objects.requireNonNull(localActivitySlotSupplier);
  }

  @Nonnull
  @Override
  public SlotSupplier<WorkflowSlotInfo> getWorkflowTaskSlotSupplier() {
    return workflowTaskSlotSupplier;
  }

  @Nonnull
  @Override
  public SlotSupplier<ActivitySlotInfo> getActivityTaskSlotSupplier() {
    return activityTaskSlotSupplier;
  }

  @Nonnull
  @Override
  public SlotSupplier<LocalActivitySlotInfo> getLocalActivitySlotSupplier() {
    return localActivitySlotSupplier;
  }
}
