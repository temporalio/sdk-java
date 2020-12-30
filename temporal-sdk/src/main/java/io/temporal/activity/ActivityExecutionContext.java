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

import com.uber.m3.tally.Scope;
import io.temporal.client.ActivityCompletionClient;
import io.temporal.client.ActivityCompletionException;
import io.temporal.worker.WorkerOptions;
import java.lang.reflect.Type;
import java.util.Optional;

/**
 * Context object passed to an activity implementation. Use {@link Activity#getExecutionContext()}
 * from an activity implementation to access.
 *
 * @author fateev
 */
public interface ActivityExecutionContext {

  /** Information about activity invocation and the caller workflow */
  ActivityInfo getInfo();

  /**
   * Use to notify Simple Workflow that activity execution is alive.
   *
   * @param details In case of activity timeout details are returned as a field of the exception
   *     thrown.
   * @throws ActivityCompletionException Indicates that activity cancellation was requested by the
   *     workflow or any other reason for activity to stop execution. Should be rethrown from
   *     activity implementation to indicate successful cancellation.
   */
  <V> void heartbeat(V details) throws ActivityCompletionException;

  /**
   * Extracts heartbeat details from the last failed attempt. This is used in combination with retry
   * options. An activity could be scheduled with an optional {@link
   * io.temporal.common.RetryOptions} on {@link io.temporal.activity.ActivityOptions}. If an
   * activity failed then the server would attempt to dispatch another activity task to retry
   * according to the retry options. If there was heartbeat details reported by the activity from
   * the failed attempt, the details would be delivered along with the activity task for the retry
   * attempt. The activity could extract the details by {@link #getHeartbeatDetails(Class)}() and
   * resume from the progress.
   *
   * @param detailsClass type of the heartbeat details
   */
  <V> Optional<V> getHeartbeatDetails(Class<V> detailsClass);

  /**
   * Extracts heartbeat details from the last failed attempt. This is used in combination with retry
   * options. An activity could be scheduled with an optional {@link
   * io.temporal.common.RetryOptions} on {@link io.temporal.activity.ActivityOptions}. If an
   * activity failed then the server would attempt to dispatch another activity task to retry
   * according to the retry options. If there was heartbeat details reported by the activity from
   * the failed attempt, the details would be delivered along with the activity task for the retry
   * attempt. The activity could extract the details by {@link #getHeartbeatDetails(Class)}() and
   * resume from the progress.
   *
   * @param detailsClass type of the heartbeat details
   */
  <V> Optional<V> getHeartbeatDetails(Class<V> detailsClass, Type detailsType);

  /**
   * A correlation token that can be used to complete the activity asynchronously through {@link
   * io.temporal.client.ActivityCompletionClient#complete(byte[], Object)}.
   */
  byte[] getTaskToken();

  /**
   * If this method is called during an activity execution then activity is not going to complete
   * when its method returns. It is expected to be completed asynchronously using {@link
   * io.temporal.client.ActivityCompletionClient}.
   */
  void doNotCompleteOnReturn();

  boolean isDoNotCompleteOnReturn();

  /** Returns true if {@link #useLocalManualCompletion()} method has been called on this context. */
  boolean isUseLocalManualCompletion();

  /**
   * Local manual completion, sets {@link #doNotCompleteOnReturn()} flag making activity completion
   * asynchronous, also returns completion client. Returned completion client must be used to
   * complete the activity on the same machine. Main difference from calling {@link
   * #doNotCompleteOnReturn()} directly is that by using this method maximum number of concurrent
   * activities defined by {@link WorkerOptions#getMaxConcurrentActivityExecutionSize()} will be
   * respected.
   */
  ActivityCompletionClient useLocalManualCompletion();

  Scope getMetricsScope();
}
