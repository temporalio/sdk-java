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

package io.temporal.internal.sync;

import io.temporal.workflow.NexusOperationHandle;
import io.temporal.workflow.Promise;
import java.util.Optional;

public class NexusOperationHandleImpl<R> implements NexusOperationHandle<R> {
  Promise<Optional<String>> operationExecution;
  Promise<R> result;

  public NexusOperationHandleImpl(Promise<Optional<String>> operationExecution, Promise<R> result) {
    this.operationExecution = operationExecution;
    this.result = result;
  }

  @Override
  public Promise<Optional<String>> getExecution() {
    return operationExecution;
  }

  @Override
  public Promise<R> getResult() {
    return result;
  }
}
