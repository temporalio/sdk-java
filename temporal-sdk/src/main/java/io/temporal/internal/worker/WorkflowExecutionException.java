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

import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.failure.v1.Failure;

/**
 * Internal. Do not throw or catch in application level code.
 *
 * <p>This exception is used to signal that the workflow execution should be failed with {@link
 * CommandType#COMMAND_TYPE_FAIL_WORKFLOW_EXECUTION}
 */
public final class WorkflowExecutionException extends RuntimeException {
  private final Failure failure;

  public WorkflowExecutionException(Failure failure) {
    super(failure.getMessage());
    this.failure = failure;
  }

  public Failure getFailure() {
    return failure;
  }
}
