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

/**
 * @package io.temporal.activity
 */
package io.temporal.activity;

import com.uber.m3.tally.Scope;
import io.temporal.client.ActivityCompletionException;
import io.temporal.worker.WorkerOptions;
import java.lang.reflect.Type;
import java.util.Optional;

/**
 * @entity ActivityExecutionContext
 * @entity.type Interface
 * @entity.headline Context object passed to an Activity implementation
 * @entity.description Use {@link Activity.getExecutionContext}
 * from an Activity implementation to access.
 */
public interface ActivityExecutionContext {

  /**
   * @feature getInfo
   * @feature.type Method
   * @feature.headline Get Information about an Activity
   * @feature.description Implementations of this interface can be used to retrieve information
   * about the Activity invocation and the calling Workflow.
   * @feature.returns {@link ActivityInfo}
   */
  ActivityInfo getInfo();

  /**
   * @feature heartbeat
   * @feature.type Method
   * @feature.headline Use to notify simple Workflow that an Activity execution is alive
   * @feature.description
   * @feature.param Any Details in case of Activity timeout, details are returned
   * as a field of the exception that is thrown.
   * @feature.throws ActivityCompletionException Indicates that Activity cancellation
   * was requested by the Workflow, or there is another reason for the Activity to stop execution.
   * Should be rethrown from Activity implementation to indicate a successful cancellation.
   */
  <V> void heartbeat(V details) throws ActivityCompletionException;

  /**
   * @feature getHeartbeatDetails
   * @feature.type Method
   * @feature.headline Extracts heartbeat details from the last failed attempt.
   * @feature.description This is used in combination with retry options.
   * An Activity could be scheduled with optional {@link RetryOptions} via {@link ActivityOptions}.
   * If an Activity failed then the server would attempt to dispatch another Activity task to retry
   * according to the retry options. If there was heartbeat details reported by the Activity from
   * the failed attempt, the details would be delivered along with the Activity task for the retry
   * attempt. The Activity could extract the details via this method and resume from the progress.
   * @feature.param Class Class of the heartbeat details
   * @feature.param Type Optional. Type of the heartbeat details
   */
  <V> Optional<V> getHeartbeatDetails(Class<V> detailsClass);

  /**
   * Extracts heartbeat details from the last failed attempt.
   * This is used in combination with retry options.
   * An Activity could be scheduled with an optional {@link
   * io.temporal.common.RetryOptions} on {@link io.temporal.activity.ActivityOptions}. If an
   * Activity failed then the server would attempt to dispatch another Activity task to retry
   * according to the retry options. If there was heartbeat details reported by the Activity from
   * the failed attempt, the details would be delivered along with the Activity task for the retry
   * attempt. The Activity could extract the details by {@link #getHeartbeatDetails(Class)}() and
   * resume from the progress.
   *
   * @param detailsClass type of the heartbeat details
   */
  <V> Optional<V> getHeartbeatDetails(Class<V> detailsClass, Type detailsType);

  /**
   * @feature getTaskToken
   * @feature.type Method
   * @feature.headline A correlation token
   * @feature.description The token can be used to complete an Activity asynchronously
   * via {@link ActivityCompletionClient.complete}.
   * @feature.return byte[]
   */
  byte[] getTaskToken();

  /**
   * @feature doNotCompleteOnReturn
   * @feature.type Method
   * @feature.headline Activity will not complete when it returns
   * @feature.description If this method is called during an Activity execution,
   * then Activity is not going to complete when its method returns.
   * It is expected to be completed asynchronously using the {@link ActivityCompletionClient}.
   * Async Activities that have {@link ActivityExecutionContext.isUseLocalManualCompletion} set to false
   * would not respect the limit defined by {@link WorkerOptions.getMaxConcurrentActivityExecutionSize}.
   * If you want to limit the number of concurrent async Activities
   * and if you always complete those Activities from the same Activity Worker
   * you should use {@link ActivityExecutionContext.useLocalManualCompletion} instead.
   * @feature.return Void
   */
  void doNotCompleteOnReturn();

  /**
   * @feature isDoNotCompleteOnReturn
   * @feature.type Method
   * @feature.headline Returns true if {@link ActivityExecutionContext.doNotCompleteOnReturn} has been called
   * @feature.return boolean
   */
  boolean isDoNotCompleteOnReturn();

  /**
   * @feature isUseLocalManualCompletion
   * @feature.type Method
   * @feature.headline Returns true if {@link ActivityExecutionContext.useLocalManualCompletion} method has been called on this context
   * @feature.description . If this flag is set to true, {@link ActivityWorker} would not release
   * concurrency semaphore and delegate the release function to the manual Activity client returned by
   * {@link ActivityExecutionContext.useLocalManualCompletion}
   * @feature.return boolean
   */
  boolean isUseLocalManualCompletion();

  /**
   * @feature useLocalManualCompletion
   * @feature.type Method
   * @feature.headline Local manual completion
   * @feature.description Sets {@link ActivityExecutionContext.doNotCompleteOnReturn} flag
   * making Activity completion asynchronous. It also returns the completion client.
   * Returned completion client must be used to complete the Activity on the same machine.
   * Main difference from calling {@link ActivityExecutionContext.doNotCompleteOnReturn()} directly
   * is that by using this method the maximum number of concurrent Activities defined by
   * {@link WorkerOptions.getMaxConcurrentActivityExecutionSize} will be respected.
   * Users must be careful and always call completion method on the {@link ManualActivityCompletionClient}
   * otherwise Activity Worker could stop polling new Tasks as it will consider all Activities
   * that didn't explicitly finish as still running.
   * @feature.return {@link ManualActivityCompletionClient}
   */
  ManualActivityCompletionClient useLocalManualCompletion();
  
  /**
   * @feature getMetricsScope
   * @feature.type Method
   * @feature.headline Gets the metrics scope
   * @feature.return com.uber.m3.tally.Scope
   */
  Scope getMetricsScope();
}
