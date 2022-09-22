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

package io.temporal.internal.worker;

import io.temporal.api.command.v1.ScheduleActivityTaskCommandAttributesOrBuilder;
import io.temporal.api.workflowservice.v1.PollActivityTaskQueueResponse;

public interface EagerActivityDispatcher {
  boolean tryReserveActivitySlot(ScheduleActivityTaskCommandAttributesOrBuilder commandAttributes);

  void releaseActivitySlotReservations(int slotCounts);

  void dispatchActivity(PollActivityTaskQueueResponse activity);

  static class NoopEagerActivityDispatcher implements EagerActivityDispatcher {
    @Override
    public boolean tryReserveActivitySlot(
        ScheduleActivityTaskCommandAttributesOrBuilder commandAttributes) {
      return false;
    }

    @Override
    public void releaseActivitySlotReservations(int slotCounts) {
      if (slotCounts > 0)
        throw new IllegalStateException(
            "Trying to release activity slots on a NoopEagerActivityDispatcher");
    }

    @Override
    public void dispatchActivity(PollActivityTaskQueueResponse activity) {
      throw new IllegalStateException(
          "Trying to dispatch activity on a NoopEagerActivityDispatcher");
    }
  }
}
