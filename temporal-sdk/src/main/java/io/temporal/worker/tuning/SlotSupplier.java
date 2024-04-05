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
import java.util.Optional;

/**
 * A SlotSupplier is responsible for managing the number of slots available for a given type of
 * task. The three types of tasks are workflow, activity, and local activity. Implementing this
 * interface allows you to carefully control how many tasks of any given type a worker will process
 * at once.
 *
 * @param <SI> The type of information that will be used to reserve a slot. The three info types are
 *     {@link WorkflowSlotInfo}, {@link ActivitySlotInfo}, and {@link LocalActivitySlotInfo}.
 */
@Experimental
public interface SlotSupplier<SI> {
  /**
   * This function is called before polling for new tasks. Your implementation should block until a
   * slot is available, then return a permit to use that slot.
   *
   * @param ctx The context for slot reservation.
   * @return A permit to use the slot which may be populated with your own data.
   * @throws InterruptedException The worker may choose to interrupt the thread in order to cancel
   *     the reservation, or during shutdown. You may perform cleanup, and then should rethrow the
   *     exception.
   */
  SlotPermit reserveSlot(SlotReservationContext<SI> ctx) throws InterruptedException;

  /**
   * This function is called when trying to reserve slots for "eager" workflow and activity tasks.
   * Eager tasks are those which are returned as a result of completing a workflow task, rather than
   * from polling. Your implementation must not block, and If a slot is available, return a permit
   * to use that slot.
   *
   * @param ctx The context for slot reservation.
   * @return Maybe a permit to use the slot which may be populated with your own data.
   */
  Optional<SlotPermit> tryReserveSlot(SlotReservationContext<SI> ctx);

  /**
   * This function is called once a slot is actually being used to process some task, which may be
   * some time after the slot was reserved originally. For example, if there is no work for a
   * worker, a number of slots equal to the number of active pollers may already be reserved, but
   * none of them are being used yet. This call should be non-blocking.
   *
   * @param info The information about the task that will now be processed
   * @param permit The permit that this implementation gave out when a slot was reserved.
   */
  void markSlotUsed(SI info, SlotPermit permit);

  /**
   * This function is called once a permit is no longer needed. This could be because the task has
   * finished, whether successfully or not, or because the slot was no longer needed (ex: the number
   * of active pollers decreased). This call should be non-blocking.
   *
   * @param reason The reason the slot is being released.
   * @param permit The permit that was given out when the slot was reserved.
   */
  void releaseSlot(SlotReleaseReason reason, SlotPermit permit);

  /**
   * Because we currently use thread pools to execute tasks, there must be *some* defined
   * upper-limit on the size of the thread pool for each kind of task, and your implementation must
   * provide this number. You must not hand out more permits than this number.
   *
   * @return the maximum number of slots that can ever be in use at one type for this slot type.
   */
  int maximumSlots();
}
