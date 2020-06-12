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

package io.temporal.workflow;

import io.temporal.failure.CanceledException;
import io.temporal.proto.common.ParentClosePolicy;

/**
 * Defines behaviour of the parent workflow when {@link CancellationScope} that wraps child workflow
 * execution request is cancelled. The result of the cancellation independently of the type is a
 * {@link CanceledException} thrown from the child workflow method.
 */
public enum ChildWorkflowCancellationType {
  /** Wait for child cancellation completion. */
  WAIT_CANCELLATION_COMPLETED,

  /**
   * Request cancellation of the child and wait for confirmation that the request was received.
   * Doesn't wait for actual cancellation.
   */
  WAIT_CANCELLATION_REQUESTED,

  /**
   * Initiate a cancellation request and immediately report cancellation to the parent. Note that it
   * doesn't guarantee that cancellation is delivered to the child if parent exits before the
   * delivery is done. It can be mitigated by setting {@link ParentClosePolicy} to {@link
   * ParentClosePolicy#RequestCancel}.
   */
  TRY_CANCEL,

  /** Do not request cancellation of the child workflow */
  ABANDON,
}
