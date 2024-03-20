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

import java.util.Optional;

public interface SlotSupplier<SI> {
  SlotPermit reserveSlot(SlotReservationContext<SI> ctx) throws InterruptedException;

  Optional<SlotPermit> tryReserveSlot(SlotReservationContext<SI> ctx);

  void markSlotUsed(SI info, SlotPermit permit);

  void releaseSlot(SlotReleaseReason reason, SlotPermit permit);

  /**
   * Because we currently use thread pools to execute tasks, there must be *some* defined
   * upper-limit on the size of the thread pool for each kind of task.
   *
   * @return the maximum number of slots that can ever be in use at one type for this slot type.
   */
  int maximumSlots();
}
