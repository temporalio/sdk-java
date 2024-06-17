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
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** Implements a {@link SlotSupplier} based on resource usage for a particular slot type. */
@Experimental
public class ResourceBasedSlotSupplier<SI extends SlotInfo, RI extends SystemResourceInfo>
    implements SlotSupplier<SI> {

  private final ResourceController<RI> resourceController;
  private final ResourceBasedSlotOptions options;
  private final Instant lastSlotIssuedAt = Instant.EPOCH;

  /**
   * Construct a slot supplier with the given resource controller and options.
   *
   * <p>The resource controller must be the same among all slot suppliers in a worker. If you want
   * to use resource-based tuning for all slot suppliers, prefer {@link ResourceBasedTuner}.
   */
  public ResourceBasedSlotSupplier(
      ResourceController<RI> resourceController, ResourceBasedSlotOptions options) {
    this.resourceController = resourceController;
    this.options = options;
  }

  @Override
  public SlotPermit reserveSlot(SlotReserveContext<SI> ctx) throws InterruptedException {
    while (true) {
      if (ctx.getNumIssuedSlots() < options.getMinimumSlots()) {
        return new SlotPermit();
      } else {
        Duration mustWaitFor;
        try {
          mustWaitFor = options.getRampThrottle().minus(timeSinceLastSlotIssued());
        } catch (ArithmeticException e) {
          mustWaitFor = Duration.ZERO;
        }
        if (mustWaitFor.compareTo(Duration.ZERO) > 0) {
          Thread.sleep(mustWaitFor.toMillis());
        }

        Optional<SlotPermit> permit = tryReserveSlot(ctx);
        if (permit.isPresent()) {
          return permit.get();
        } else {
          Thread.sleep(10);
        }
      }
    }
  }

  @Override
  public Optional<SlotPermit> tryReserveSlot(SlotReserveContext<SI> ctx) {
    int numIssued = ctx.getNumIssuedSlots();
    if (numIssued < options.getMinimumSlots()
        || (timeSinceLastSlotIssued().compareTo(options.getRampThrottle()) > 0
            && numIssued < options.getMaximumSlots()
            && resourceController.pidDecision())) {
      return Optional.of(new SlotPermit());
    }
    return Optional.empty();
  }

  @Override
  public void markSlotUsed(SlotMarkUsedContext<SI> ctx) {}

  @Override
  public void releaseSlot(SlotReleaseContext<SI> ctx) {}

  ResourceController<RI> getResourceController() {
    return resourceController;
  }

  private Duration timeSinceLastSlotIssued() {
    return Duration.between(lastSlotIssuedAt, Instant.now());
  }
}
