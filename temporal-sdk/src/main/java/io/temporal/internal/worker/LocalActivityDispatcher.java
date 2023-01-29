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

package io.temporal.internal.worker;

import io.grpc.Deadline;
import io.temporal.internal.statemachines.ExecuteLocalActivityParameters;
import io.temporal.workflow.Functions;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface LocalActivityDispatcher {
  /**
   * Synchronously dispatches the local activity to the local activity worker.
   *
   * @return true if the local activity was accepted, false if it was rejected
   * @throws IllegalStateException if the local activity worker was not started
   * @throws IllegalArgumentException if the local activity type is not supported
   */
  boolean dispatch(
      @Nonnull ExecuteLocalActivityParameters params,
      @Nonnull Functions.Proc1<LocalActivityResult> resultCallback,
      @Nullable Deadline acceptanceDeadline);
}
