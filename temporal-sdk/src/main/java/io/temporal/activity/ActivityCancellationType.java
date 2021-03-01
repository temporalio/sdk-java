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

import io.temporal.failure.CanceledFailure;
import io.temporal.workflow.CancellationScope;

/**
 * @entity ActivityCancellationType
 * @entity.type Enum
 * @entity.headline Defines behavior of the cancellation
 * @entity.description Defines behavior of the parent Workflow when {@link CancellationScope},
 * that wraps the child Workflow execution request, is canceled.
 * The result of the cancellation, independent of the type, is {@link CanceledFailure}
 * which is thrown from the child Workflow method.
 */
public enum ActivityCancellationType {

  /**
   * @feature WAIT_CANCELLATION_COMPLETED
   * @feature.type Value
   * @feature.headline Wait for Activity cancellation completion
   * @feature.description Use this to wait for an Activity cancellation to be completed.
   * The Activity must heartbeat to receive a cancellation notification.
   * This can block the cancellation for a long time if the Activity doesn't
   * heartbeat or chooses to ignore the cancellation request.
   */
  WAIT_CANCELLATION_COMPLETED,

  /**
   * @feature TRY_CANCEL
   * @feature.type Value
   * @feature.headline Initiates a cancellation request
   * @feature.description Initiates a cancellation request
   * and immediately reports cancellation to the Workflow.
   */
  TRY_CANCEL,
  
  /**
   * @feature ABANDON
   * @feature.type Value
   * @feature.headline Does not initiate a cancellation
   * @feature.description  Does not initiate a cancellation of the Activity,
   * but immediately report cancellation to the Workflow.
   */
  ABANDON,
}
