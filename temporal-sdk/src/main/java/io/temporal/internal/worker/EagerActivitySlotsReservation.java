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

import io.temporal.api.command.v1.Command;
import io.temporal.api.command.v1.ScheduleActivityTaskCommandAttributes;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.workflowservice.v1.PollActivityTaskQueueResponse;
import io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedRequest;
import io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedResponse;

public class EagerActivitySlotsReservation implements AutoCloseable {
  private final EagerActivityInjector eagerActivityInjector;
  private int acquiredReservationCount = 0;

  EagerActivitySlotsReservation(
      EagerActivityInjector eagerActivityInjector,
      RespondWorkflowTaskCompletedRequest.Builder mutableRequest) {
    this.eagerActivityInjector = eagerActivityInjector;
    applyToRequest(mutableRequest);
  }

  public void applyToRequest(RespondWorkflowTaskCompletedRequest.Builder mutableRequest) {
    for (int i = 0; i < mutableRequest.getCommandsCount(); i++) {
      Command command = mutableRequest.getCommands(i);
      if (command.getCommandType() != CommandType.COMMAND_TYPE_SCHEDULE_ACTIVITY_TASK) continue;

      ScheduleActivityTaskCommandAttributes commandAttributes =
          command.getScheduleActivityTaskCommandAttributes();
      if (!commandAttributes.getRequestEagerExecution()) continue;

      if (this.eagerActivityInjector.tryReserveActivitySlot(commandAttributes)) {
        this.acquiredReservationCount++;
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
    if (serverResponse.getActivityTasksCount() > this.acquiredReservationCount)
      throw new IllegalStateException(
          String.format(
              "Unexpectedly received %d eager activities though we only requested %d",
              serverResponse.getActivityTasksCount(), this.acquiredReservationCount));

    if (this.acquiredReservationCount == 0) return;

    for (PollActivityTaskQueueResponse act : serverResponse.getActivityTasksList())
      this.eagerActivityInjector.injectActivity(act);

    this.eagerActivityInjector.releaseActivitySlotReservations(
        this.acquiredReservationCount - serverResponse.getActivityTasksCount());

    // At this point, all reservations have been either freed or transferred to actual activities
    this.acquiredReservationCount = 0;
  }

  @Override
  public void close() {
    if (this.acquiredReservationCount > 0) {
      this.eagerActivityInjector.releaseActivitySlotReservations(this.acquiredReservationCount);
      this.acquiredReservationCount = 0;
    }
  }
}
