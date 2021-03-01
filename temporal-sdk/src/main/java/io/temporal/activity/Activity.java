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

import io.temporal.common.converter.DataConverter;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ChildWorkflowFailure;
import io.temporal.internal.sync.ActivityInternal;
import io.temporal.internal.sync.WorkflowInternal;

/**
 * @entity Activity
 * @entity.type Class
 * @entity.headline Defines Activity objects
 * @entity.description This class defines the non-deterministic business logic methods that
 * are defined by the {@link ActivityInterface} annotation.
 */
public final class Activity {

  /**
   * @feature getExecutionContext
   * @feature.headline Get the ActvityExecutionContext of the Activity
   * @feature.description Used to get information about the Activity invocation.
   * And can be used for Activity Heartbeats.
   * This static method relies on a local thread and only works in the original Activity
   * thread.
   * @feature.type Method
   * @feature.return {@link ActivityExecutionContext}
   */
  public static ActivityExecutionContext getExecutionContext() {
    return ActivityInternal.getExecutionContext();
  }

  /*
   * @feature wrap
   * @feature.type Method
   * @feature.headline Throws a wrapped error
   * @feature.throws Throwable error
   * feature.return never returns as always throws.
   * @feature.description Throws original exception if e is {@link RuntimeException} or {@link Error}
   */
  public static RuntimeException wrap(Throwable e) {
    return WorkflowInternal.wrap(e);
  }
  
  /** Prohibit instantiation */
  private Activity() {}
}
