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

/** Defines Activity cancellation behavior. */
public enum ActivityCancellationType {
  /**
   * Wait for the Activity to confirm any requested cancellation. Note that an Activity must
   * Heartbeat to receive a cancellation notification. This can block the cancellation for a long
   * time if the Activity doesn't Heartbeat or chooses to ignore the cancellation request.
   */
  WAIT_CANCELLATION_COMPLETED,

  /** Initiate a cancellation request and immediately report cancellation to the Workflow. */
  TRY_CANCEL,

  /**
   * Do not request cancellation of the Activity and immediately report cancellation to the
   * Workflow.
   */
  ABANDON,
}
