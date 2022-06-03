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

package io.temporal.client;

import io.temporal.activity.ActivityInfo;
import io.temporal.worker.WorkerFactory;
import java.util.concurrent.TimeUnit;

/**
 * Indicates that {@link WorkerFactory#shutdown()} or {@link WorkerFactory#shutdownNow()} was
 * called. It is OK to ignore the exception to let activity to complete. It assumes that {@link
 * WorkerFactory#awaitTermination(long, TimeUnit)} is called with a timeout larger than the activity
 * execution time.
 */
public final class ActivityWorkerShutdownException extends ActivityCompletionException {

  public ActivityWorkerShutdownException(ActivityInfo info) {
    super(info);
  }

  public ActivityWorkerShutdownException() {
    super();
  }
}
