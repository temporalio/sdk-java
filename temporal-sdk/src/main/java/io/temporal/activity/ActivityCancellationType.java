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

package io.temporal.activity;

import io.temporal.client.ActivityCompletionException;

/**
 * In case of an activity's call scope cancellation the corresponding activity stub call fails with
 * a {@link io.temporal.failure.CanceledFailure}. The different modes of this behavior are available
 * and specified by this enum.
 */
public enum ActivityCancellationType {
  /**
   * Wait for the Activity Execution to confirm any requested cancellation. An Activity Execution
   * must Heartbeat to receive a cancellation notification through {@link
   * ActivityCompletionException}. This can block the cancellation of a Workflow Execution for a
   * long time if the Activity Execution doesn't Heartbeat or chooses to ignore the cancellation
   * request. The activity stub call will fail with {@link io.temporal.failure.CanceledFailure} only
   * after cancellation confirmation from the Activity Execution has been received.
   */
  WAIT_CANCELLATION_COMPLETED,

  /**
   * In case of activity's scope cancellation send an Activity cancellation request to the server,
   * and report cancellation to the Workflow Execution by causing the activity stub call to fail
   * with {@link io.temporal.failure.CanceledFailure}
   */
  TRY_CANCEL,

  /**
   * Do not request cancellation of the Activity Execution at all (no request is sent to the server)
   * and immediately report cancellation to the Workflow Execution by causing the activity stub call
   * to fail with {@link io.temporal.failure.CanceledFailure} immediately.
   */
  ABANDON,
}
