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

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public class ExecutorThreadFactory implements ThreadFactory {
  private final String threadPrefix;

  private final Thread.UncaughtExceptionHandler uncaughtExceptionHandler;
  private final AtomicInteger threadIndex = new AtomicInteger();

  public ExecutorThreadFactory(String threadPrefix, Thread.UncaughtExceptionHandler eh) {
    this.threadPrefix = threadPrefix;
    this.uncaughtExceptionHandler = eh;
  }

  @Override
  public Thread newThread(Runnable r) {
    Thread result = new Thread(r);
    result.setName(threadPrefix + ": " + threadIndex.incrementAndGet());
    result.setUncaughtExceptionHandler(uncaughtExceptionHandler);
    return result;
  }
}
