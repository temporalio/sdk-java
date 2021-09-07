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

package io.temporal.client;

import io.temporal.activity.ActivityInfo;
import io.temporal.worker.WorkerFactory;
import java.util.concurrent.TimeUnit;

/**
 * Indicates that {@link WorkerFactory#shutdown()} or {@link WorkerFactory#shutdownNow()} was
 * called. It is OK to ignore the exception to let activity complete. It assumes that {@link
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
