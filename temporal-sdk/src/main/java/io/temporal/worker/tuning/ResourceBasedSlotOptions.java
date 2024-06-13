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

/** Options resource-based slot suppliers */
@Experimental
public class ResourceBasedSlotOptions {
  private final int minimumSlots;
  private final int maximumSlots;
  private final Duration rampThrottle;

  /**
   * @param minimumSlots minimum number of slots that will be issued without any resource checks
   * @param maximumSlots maximum number of slots that will ever be issued
   * @param rampThrottle time to wait between slot issuance. This value matters because how many
   *     resources a task will use cannot be determined ahead of time, and thus the system should
   *     ait to see how much resources are used before issuing more slots.
   */
  public ResourceBasedSlotOptions(int minimumSlots, int maximumSlots, Duration rampThrottle) {
    this.minimumSlots = minimumSlots;
    this.maximumSlots = maximumSlots;
    this.rampThrottle = rampThrottle;
  }

  public int getMinimumSlots() {
    return minimumSlots;
  }

  public int getMaximumSlots() {
    return maximumSlots;
  }

  public Duration getRampThrottle() {
    return rampThrottle;
  }
}
