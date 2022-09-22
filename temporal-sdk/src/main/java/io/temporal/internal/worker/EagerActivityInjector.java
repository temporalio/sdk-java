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

public interface EagerActivityInjector {
  // Perform an early check at either there is any possibility that requesting eager execution on
  // the given activity
  // command would be accepted. This is an optimisation, to reduce late mutations of
  // ScheduleActivityTaskCommand.
  public boolean canRequestEagerExecution(
      ScheduleActivityTaskCommandAttributesOrBuilder commandAttributes);

  public boolean tryReserveActivitySlot(
      ScheduleActivityTaskCommandAttributesOrBuilder commandAttributes);

  public void releaseActivitySlotReservations(int slotCounts);

  public void injectActivity(PollActivityTaskQueueResponse activity);

  static class NoopEagerActivityInjector implements EagerActivityInjector {
    @Override
    public boolean canRequestEagerExecution(
        ScheduleActivityTaskCommandAttributesOrBuilder commandAttributes) {
      return false;
    }

    @Override
    public boolean tryReserveActivitySlot(
        ScheduleActivityTaskCommandAttributesOrBuilder commandAttributes) {
      return false;
    }

    @Override
    public void releaseActivitySlotReservations(int slotCounts) {
      if (slotCounts > 0)
        throw new IllegalStateException(
            "Trying to release activity slots on a NoopEagerActivityInjector");
    }

    @Override
    public void injectActivity(PollActivityTaskQueueResponse activity) {
      throw new IllegalStateException("Trying to inject activity on a NoopEagerActivityInjector");
    }
  }
}
