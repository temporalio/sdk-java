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

package io.temporal.workflow;

import io.temporal.common.Experimental;
import java.util.Optional;

/**
 * OperationHandle is used to interact with a scheduled nexus operation. Created through {@link
 * Workflow#startNexusOperation}.
 */
@Experimental
public interface NexusOperationHandle<R> {
  /**
   * Returns a promise that is resolved when the operation reaches the STARTED state. For
   * synchronous operations, this will be resolved at the same time as the promise from
   * executeAsync. For asynchronous operations, this promises is resolved independently. If the
   * operation is unsuccessful, this promise will throw the same exception as executeAsync. Use this
   * method to extract the Operation ID of an asynchronous operation. OperationID will be empty for
   * synchronous operations. If the workflow completes before this promise is ready then the
   * operation might not start at all.
   *
   * @return promise that becomes ready once the operation has started.
   */
  Promise<Optional<String>> getExecution();

  /** Returns a promise that will be resolved when the operation completes. */
  Promise<R> getResult();
}
