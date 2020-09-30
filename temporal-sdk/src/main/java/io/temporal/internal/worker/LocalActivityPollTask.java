/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
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
    implements Poller.PollTask<LocalActivityWorker.Task>,
        BiFunction<LocalActivityWorker.Task, Duration, Boolean> {
  private static final Logger log = LoggerFactory.getLogger(LocalActivityPollTask.class);

  private static final int QUEUE_SIZE = 1000;
  private final BlockingQueue<LocalActivityWorker.Task> pendingTasks =
      new ArrayBlockingQueue<>(QUEUE_SIZE);

  @Override
  public LocalActivityWorker.Task poll() {
    try {
      LocalActivityWorker.Task task = pendingTasks.take();
      if (log.isTraceEnabled()) {
        log.trace("LocalActivity Task poll returned: " + task.getActivityId());
      }
      return task;

    } catch (InterruptedException e) {
      throw new RuntimeException("local activity poll task interrupted", e);
    }
  }

  @Override
  public Boolean apply(LocalActivityWorker.Task task, Duration maxWaitAllowed) {
    try {
      boolean accepted = pendingTasks.offer(task, maxWaitAllowed.toMillis(), TimeUnit.MILLISECONDS);
      if (log.isTraceEnabled()) {
        if (accepted) {
          log.trace("LocalActivity queued: " + task.getActivityId());
        } else {
          log.trace(
              "LocalActivity queue timed out for "
                  + task.getActivityId()
                  + " maxWaitAllowed="
                  + maxWaitAllowed);
        }
      }
      return accepted;
    } catch (InterruptedException e) {
      return false;
    }
  }
}
