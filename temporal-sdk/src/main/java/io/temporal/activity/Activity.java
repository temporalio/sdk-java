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

import io.temporal.internal.sync.ActivityInternal;
import io.temporal.internal.sync.WorkflowInternal;

/**
 * An Activity is the implementation of a particular task in the business logic.
 *
 * @see io.temporal.worker.Worker
 * @see io.temporal.workflow.Workflow
 * @see io.temporal.client.WorkflowClient
 */
public final class Activity {

  /**
   * Can be used to get information about an Activity Execution and to invoke Heartbeats. This
   * static method relies on a thread-local variable and works only in the original Activity
   * Execution thread.
   */
  public static ActivityExecutionContext getExecutionContext() {
    return ActivityInternal.getExecutionContext();
  }

  /**
   * Use this to rethrow a checked exception from an Activity Execution instead of adding the
   * exception to a method signature.
   *
   * @return Never returns; always throws. Throws original exception if e is {@link
   *     RuntimeException} or {@link Error}.
   */
  public static RuntimeException wrap(Throwable e) {
    return WorkflowInternal.wrap(e);
  }

  /** Prohibits instantiation. */
  private Activity() {}
}
