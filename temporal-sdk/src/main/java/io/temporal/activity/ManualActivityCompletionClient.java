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

package io.temporal.activity;

import io.temporal.failure.CanceledFailure;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * This client is attached to a specific activity execution and let user report completion
 * (successful, failed or confirm cancellation) and perform heartbeats.
 *
 * <p>May be obtained by calling {@link ActivityExecutionContext#useLocalManualCompletion()}
 */
public interface ManualActivityCompletionClient {

  /**
   * Completes the activity execution successfully.
   *
   * @param result of the activity execution
   */
  void complete(@Nullable Object result);

  /**
   * Completes the activity execution with failure.
   *
   * @param failure the exception to be used as a failure details object
   */
  void fail(@Nonnull Throwable failure);

  /**
   * Records heartbeat for an activity
   *
   * @param details to record with the heartbeat
   */
  void recordHeartbeat(@Nullable Object details) throws CanceledFailure;

  /**
   * Confirms successful cancellation to the server.
   *
   * @param details to record with the cancellation
   */
  void reportCancellation(@Nullable Object details);
}
