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

import io.temporal.common.Experimental;
import javax.annotation.Nonnull;

/** WorkerTuners allow for the dynamic customization of some aspects of worker configuration. */
@Experimental
public interface WorkerTuner {
  /**
   * @return A {@link SlotSupplier} for workflow tasks.
   */
  @Nonnull
  SlotSupplier<WorkflowSlotInfo> getWorkflowTaskSlotSupplier();

  /**
   * @return A {@link SlotSupplier} for activity tasks.
   */
  @Nonnull
  SlotSupplier<ActivitySlotInfo> getActivityTaskSlotSupplier();

  /**
   * @return A {@link SlotSupplier} for local activities.
   */
  @Nonnull
  SlotSupplier<LocalActivitySlotInfo> getLocalActivitySlotSupplier();

  /**
   * @return A {@link SlotSupplier} for nexus tasks.
   */
  @Nonnull
  SlotSupplier<NexusSlotInfo> getNexusSlotSupplier();
}
