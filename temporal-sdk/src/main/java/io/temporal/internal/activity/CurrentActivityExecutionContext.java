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

package io.temporal.internal.activity;

import io.temporal.activity.ActivityExecutionContext;

/**
 * Thread local store of the context object passed to an activity implementation. Avoid using this
 * class directly.
 *
 * @author fateev
 */
final class CurrentActivityExecutionContext {

  private static final ThreadLocal<ActivityExecutionContext> CURRENT = new ThreadLocal<>();

  /**
   * This is used by activity implementation to get access to the current ActivityExecutionContext
   */
  public static ActivityExecutionContext get() {
    ActivityExecutionContext result = CURRENT.get();
    if (result == null) {
      throw new IllegalStateException(
          "ActivityExecutionContext can be used only inside of activity "
              + "implementation methods and in the same thread that invoked an activity.");
    }
    return result;
  }

  public static boolean isSet() {
    return CURRENT.get() != null;
  }

  public static void set(ActivityExecutionContext context) {
    if (context == null) {
      throw new IllegalArgumentException("null context");
    }
    if (CURRENT.get() != null) {
      throw new IllegalStateException("current already set");
    }
    CURRENT.set(context);
  }

  public static void unset() {
    CURRENT.set(null);
  }

  private CurrentActivityExecutionContext() {}
}
