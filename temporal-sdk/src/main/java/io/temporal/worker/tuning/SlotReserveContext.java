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
import java.util.Map;

@Experimental
public interface SlotReserveContext<SI extends SlotInfo> {
  /**
   * @return the Task Queue for which this reservation request is associated.
   */
  String getTaskQueue();

  /**
   * @return A read-only & safe for concurrent access mapping of slot permits to the information
   *     associated with the in-use slot. This map is changed internally any time new slots are
   *     used.
   */
  Map<SlotPermit, SI> getUsedSlots();

  /**
   * @return The worker's identity that is associated with this reservation request.
   */
  String getWorkerIdentity();

  /**
   * @return The worker's build ID that is associated with this reservation request.
   */
  String getWorkerBuildId();
}
