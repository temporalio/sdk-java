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

import io.temporal.api.enums.v1.TaskQueueKind;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
public class StickyQueueBalancer {
  private final int pollersCount;
  private final boolean stickyQueueEnabled;
  private final AtomicInteger stickyPollers = new AtomicInteger(0);
  private final AtomicInteger normalPollers = new AtomicInteger(0);

  private volatile long stickyBacklogSize = 0;

  public StickyQueueBalancer(int pollersCount, boolean stickyQueueEnabled) {
    this.pollersCount = pollersCount;
    this.stickyQueueEnabled = stickyQueueEnabled;
  }

  /**
   * @return task queue kind that should be used for the next poll
   */
  public TaskQueueKind makePoll() {
    if (stickyQueueEnabled) {
      // If pollersCount >= stickyBacklogSize > 0 we want to go back to a normal ratio to avoid a
      // situation that too many pollers (all of them in the worst case) will open only sticky queue
      // polls observing a stickyBacklogSize == 1 for example (which actually can be 0 already at
      // that moment) and get stuck causing dip in worker load.
      if (stickyBacklogSize > pollersCount || stickyPollers.get() <= normalPollers.get()) {
        stickyPollers.incrementAndGet();
        return TaskQueueKind.TASK_QUEUE_KIND_STICKY;
      }
    }
    normalPollers.incrementAndGet();
    return TaskQueueKind.TASK_QUEUE_KIND_NORMAL;
  }

  /**
   * @param taskQueueKind what kind of task queue poll was just finished
   */
  public void finishPoll(TaskQueueKind taskQueueKind) {
    switch (taskQueueKind) {
      case TASK_QUEUE_KIND_NORMAL:
        normalPollers.decrementAndGet();
        break;
      case TASK_QUEUE_KIND_STICKY:
        stickyPollers.decrementAndGet();
        break;
      default:
        throw new IllegalArgumentException("Invalid task queue kind: " + taskQueueKind);
    }
  }

  /**
   * @param taskQueueKind what kind of task queue poll was just finished
   * @param backlogSize backlog size from the poll response, helps to determine if the sticky queue
   *     is backlogged
   */
  public void finishPoll(TaskQueueKind taskQueueKind, long backlogSize) {
    finishPoll(taskQueueKind);
    if (TaskQueueKind.TASK_QUEUE_KIND_STICKY.equals(taskQueueKind)) {
      stickyBacklogSize = backlogSize;
    }
  }
}
