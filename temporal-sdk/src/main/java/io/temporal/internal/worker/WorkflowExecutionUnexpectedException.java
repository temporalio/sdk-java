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

import io.temporal.api.failure.v1.Failure;
import io.temporal.worker.WorkflowImplementationOptions;

/**
 * Internal. Do not throw or catch in application level code.
 *
 * <p>This exception is used to signal that the workflow execution threw an exception that is not a
 * {@link io.temporal.failure.TemporalException} and not specified on {@link
 * WorkflowImplementationOptions.Builder#setFailWorkflowExceptionTypes(Class[])}.
 *
 * <p>In this case Temporal SDK fails a workflow task and let user fix the problem instead of
 * failing the workflow execution.
 */
public class WorkflowExecutionUnexpectedException extends Exception {
  private final Failure failure;

  public WorkflowExecutionUnexpectedException(Failure failure) {
    super(failure.getMessage());
    this.failure = failure;
  }

  public Failure getFailure() {
    return failure;
  }
}
