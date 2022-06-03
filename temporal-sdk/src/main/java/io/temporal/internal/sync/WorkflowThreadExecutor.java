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

package io.temporal.internal.sync;

import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import javax.annotation.Nonnull;

/**
 * Abstracts the {@link java.util.concurrent.ThreadPoolExecutor} that is used to submit workflow
 * thread tasks to allow higher levels to define additional wrapping logic like worker-wide metric
 * reporting.
 */
public interface WorkflowThreadExecutor {
  /**
   * Submits a Runnable task for execution and returns a Future representing that task. The Future's
   * {@code get} method will return {@code null} upon <em>successful</em> completion.
   *
   * <p>This method's descriptor is a 1-1 copy of {@link
   * java.util.concurrent.ThreadPoolExecutor#submit(Runnable)}
   *
   * @param task the task to submit
   * @return a Future representing pending completion of the task
   * @throws RejectedExecutionException if the task cannot be scheduled for execution
   * @throws NullPointerException if the task is null
   */
  Future<?> submit(@Nonnull Runnable task);
}
