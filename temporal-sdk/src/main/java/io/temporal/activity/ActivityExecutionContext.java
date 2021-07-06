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
import io.temporal.client.ActivityCompletionException;
import io.temporal.worker.WorkerOptions;
import java.lang.reflect.Type;
import java.util.Optional;

/**
 * Context object passed to an Activity implementation. Use {@link Activity#getExecutionContext()}
 * from an activity implementation to access.
 *
 * @author fateev
 */
public interface ActivityExecutionContext {

  /** Information about the Activity Execution and the Workflow Execution that invoked it. */
  ActivityInfo getInfo();

  /**
   * Use to notify the Workflow Execution that the Activity Execution is alive.
   *
   * @param details In case the Activity Execution times out details are returned as a field of the
   *     exception that is thrown. The details are also accessible through {@link
   *     #getHeartbeatDetails(Class)}() on the next Activity Execution retry.
   * @throws ActivityCompletionException Which indicates that cancellation of the Activity Execution
   *     was requested by the Workflow Execution. Or it could indicate any other reason for an
   *     Activity Execution to stop. Should be rethrown from the Activity implementation to indicate
   *     a successful cancellation.
   */
  <V> void heartbeat(V details) throws ActivityCompletionException;

  /**
   * Extracts Heartbeat details from the last failed attempt. This is used in combination with retry
   * options. An Activity Execution could be scheduled with optional {@link
   * io.temporal.common.RetryOptions} via {@link io.temporal.activity.ActivityOptions}. If an
   * Activity Execution failed then the server would attempt to dispatch another Activity Task to
   * retry the execution according to the retry options. If there were Heartbeat details reported by
   * the last Activity Execution that failed, the details would be delivered along with the Activity
   * Task for the next retry attempt. The Activity implementation can extract the details via {@link
   * #getHeartbeatDetails(Class)}() and resume progress.
   *
   * @param detailsClass Class of the Heartbeat details
   */
  <V> Optional<V> getHeartbeatDetails(Class<V> detailsClass);

  /**
   * Extracts Heartbeat details from the last failed attempt. This is used in combination with retry
   * options. An Activity Execution could be scheduled with optional {@link
   * io.temporal.common.RetryOptions} via {@link io.temporal.activity.ActivityOptions}. If an
   * Activity Execution failed then the server would attempt to dispatch another Activity Task to
   * retry the execution according to the retry options. If there were Heartbeat details reported by
   * the last Activity Execution that failed, the details would be delivered along with the Activity
   * Task for the next retry attempt. The Activity implementation can extract the details via {@link
   * #getHeartbeatDetails(Class)}() and resume progress.
   *
   * @param detailsClass Class of the heartbeat details
   * @param detailsType Type of the Heatbeat details
   */
  <V> Optional<V> getHeartbeatDetails(Class<V> detailsClass, Type detailsType);

  /**
   * Gets a correlation token that can be used to complete the Activity Execution asynchronously
   * through {@link io.temporal.client.ActivityCompletionClient#complete(byte[], Object)}.
   */
  byte[] getTaskToken();

  /**
   * If this method is called during an Activity Execution then the Activity Execution is not going
   * to complete when it's method returns. It is expected to be completed asynchronously using
   * {@link io.temporal.client.ActivityCompletionClient}. Async Activity Executions that have {@link
   * #isUseLocalManualCompletion()} set to false would not respect the limit defined by {@link
   * WorkerOptions#getMaxConcurrentActivityExecutionSize()}. If you want to limit the number of
   * concurrent async Activity Executions and if you always complete those Activity Executions with
   * the same Activity Worker you should use {@link #useLocalManualCompletion()} instead.
   */
  void doNotCompleteOnReturn();

  boolean isDoNotCompleteOnReturn();

  /**
   * Returns true if {@link #useLocalManualCompletion()} method has been called on this context. If
   * this flag is set to true, {@link io.temporal.internal.worker.ActivityWorker} would not release
   * concurrency semaphore and delegate release function to the manual Activity client returned by
   * {@link #useLocalManualCompletion()}
   */
  boolean isUseLocalManualCompletion();

  /**
   * For local manual completion, sets the {@link #doNotCompleteOnReturn()} flag, making Activity
   * Execution completion asynchronous, and returns the completion client. Returned completion
   * client must be used to complete the Activity Execution on the same machine. The main difference
   * from calling {@link #doNotCompleteOnReturn()} directly is that by using this method the maximum
   * number of concurrent Activity Executions defined by {@link
   * WorkerOptions#getMaxConcurrentActivityExecutionSize()} will be respected. Users must be careful
   * and always call the completion method on the {@link ManualActivityCompletionClient} otherwise
   * the Activity Worker could stop polling as it will consider all Activity Executions, that didn't
   * explicitly finish, as still running.
   */
  ManualActivityCompletionClient useLocalManualCompletion();

  Scope getMetricsScope();
}
