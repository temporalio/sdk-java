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

import com.google.common.base.Preconditions;
import io.temporal.api.command.v1.Command;
import io.temporal.api.command.v1.ScheduleActivityTaskCommandAttributes;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.workflowservice.v1.PollActivityTaskQueueResponse;
import io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedRequest;
import io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedResponse;
import io.temporal.internal.Config;
import java.io.Closeable;
import javax.annotation.concurrent.NotThreadSafe;

/** This class is not thread safe and shouldn't leave the boundaries of one activity executor */
@NotThreadSafe
class EagerActivitySlotsReservation implements Closeable {
  private final EagerActivityDispatcher eagerActivityDispatcher;
  private int outstandingReservationSlotsCount = 0;

  EagerActivitySlotsReservation(EagerActivityDispatcher eagerActivityDispatcher) {
    this.eagerActivityDispatcher = eagerActivityDispatcher;
  }

  public void applyToRequest(RespondWorkflowTaskCompletedRequest.Builder mutableRequest) {
    for (int i = 0; i < mutableRequest.getCommandsCount(); i++) {
      Command command = mutableRequest.getCommands(i);
      if (command.getCommandType() != CommandType.COMMAND_TYPE_SCHEDULE_ACTIVITY_TASK) continue;

      ScheduleActivityTaskCommandAttributes commandAttributes =
          command.getScheduleActivityTaskCommandAttributes();
      if (!commandAttributes.getRequestEagerExecution()) continue;

      if (this.outstandingReservationSlotsCount < Config.EAGER_ACTIVITIES_LIMIT
          && this.eagerActivityDispatcher.tryReserveActivitySlot(commandAttributes)) {
        this.outstandingReservationSlotsCount++;
      } else {
        mutableRequest.setCommands(
            i,
            command.toBuilder()
                .setScheduleActivityTaskCommandAttributes(
                    commandAttributes.toBuilder().setRequestEagerExecution(false)));
      }
    }
  }

  public void handleResponse(RespondWorkflowTaskCompletedResponse serverResponse) {
    int activityTasksCount = serverResponse.getActivityTasksCount();
    Preconditions.checkArgument(
        activityTasksCount <= this.outstandingReservationSlotsCount,
        "Unexpectedly received %s eager activities though we only requested %s",
        activityTasksCount,
        this.outstandingReservationSlotsCount);

    releaseSlots(this.outstandingReservationSlotsCount - activityTasksCount);

    for (PollActivityTaskQueueResponse act : serverResponse.getActivityTasksList()) {
      // don't release slots here, instead the semaphore release reference is passed to the activity
      // worker to release when the activity is done
      this.eagerActivityDispatcher.dispatchActivity(act);
    }

    this.outstandingReservationSlotsCount = 0;
  }

  @Override
  public void close() {
    if (this.outstandingReservationSlotsCount > 0)
      releaseSlots(this.outstandingReservationSlotsCount);
  }

  private void releaseSlots(int slotsToRelease) {
    if (slotsToRelease > this.outstandingReservationSlotsCount)
      throw new IllegalStateException(
          "Trying to release more activity slots than outstanding reservations");

    this.eagerActivityDispatcher.releaseActivitySlotReservations(slotsToRelease);
    this.outstandingReservationSlotsCount -= slotsToRelease;
  }
}
