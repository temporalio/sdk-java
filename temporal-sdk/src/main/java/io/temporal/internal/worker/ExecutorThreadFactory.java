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

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

class ExecutorThreadFactory implements ThreadFactory {
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
