/*
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

package com.uber.cadence.internal.worker;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import org.apache.thrift.TException;

final class LocalActivityPollTask
    implements Poller.PollTask<LocalActivityWorker.Task>,
        BiFunction<LocalActivityWorker.Task, Duration, Boolean> {
  private static final int QUEUE_SIZE = 1000;
  private BlockingQueue<LocalActivityWorker.Task> pendingTasks =
      new ArrayBlockingQueue<>(QUEUE_SIZE);

  @Override
  public LocalActivityWorker.Task poll() throws TException {
    try {
      return pendingTasks.take();
    } catch (InterruptedException e) {
      throw new RuntimeException("local activity poll task interrupted", e);
    }
  }

  @Override
  public Boolean apply(LocalActivityWorker.Task task, Duration maxWaitAllowed) {
    try {
      pendingTasks.offer(task, maxWaitAllowed.toMillis(), TimeUnit.MILLISECONDS);
      return true;
    } catch (InterruptedException e) {
      return false;
    }
  }
}
