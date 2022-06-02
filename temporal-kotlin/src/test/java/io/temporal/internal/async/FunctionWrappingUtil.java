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

package io.temporal.internal.async;

import io.temporal.workflow.Functions;

public class FunctionWrappingUtil {
  /**
   * We emulate here what happens in Async/AsyncInternal when {@link
   * io.temporal.workflow.Async#function(Functions.Func)} accepts {@link Functions.Func<R>} as a
   * parameter and kotlin method reference is getting wrapped into {@link Functions.Func<R>}
   */
  public static <R> Functions.Func<R> temporalJavaFunctionalWrapper(Functions.Func<R> function) {
    return function;
  }
}
