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

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class LocalActivityPollTask
    implements Poller.PollTask<LocalActivityTask>,
        BiFunction<LocalActivityTask, Duration, Boolean> {
  private static final Logger log = LoggerFactory.getLogger(LocalActivityPollTask.class);

  private static final int QUEUE_SIZE = 1000;
  private final BlockingQueue<LocalActivityTask> pendingTasks =
      new ArrayBlockingQueue<>(QUEUE_SIZE);

  @Override
  public LocalActivityTask poll() {
    try {
      LocalActivityTask task = pendingTasks.take();
      log.trace("LocalActivity Task poll returned: {}", task.getActivityId());
      return task;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    }
  }

  @Override
  public Boolean apply(LocalActivityTask task, Duration maxWaitAllowed) {
    try {
      boolean accepted = pendingTasks.offer(task, maxWaitAllowed.toMillis(), TimeUnit.MILLISECONDS);
      if (accepted) {
        log.trace("LocalActivity queued: {}", task.getActivityId());
      } else {
        log.trace(
            "LocalActivity queue submitting timed out for activity {}, maxWaitAllowed: {}",
            task.getActivityId(),
            maxWaitAllowed);
      }
      return accepted;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }
}
