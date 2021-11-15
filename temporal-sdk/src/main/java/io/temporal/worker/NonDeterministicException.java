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

package io.temporal.worker;

/**
 * Thrown if history events from the server don't match commands issued by the execution or replay
 * of workflow code. <br>
 * This exception usually means that there is some form of non-determinism in workflow code that has
 * lead to a difference in the execution path taken upon replay when compared to initial execution.
 * That is to say the history the worker received for this workflow cannot be processed by the
 * current workflow code. If this happens during the replay of a new Workflow Task, this exception
 * will cause the Workflow Task to fail {@link
 * io.temporal.api.enums.v1.WorkflowTaskFailedCause#WORKFLOW_TASK_FAILED_CAUSE_NON_DETERMINISTIC_ERROR}
 */
public class NonDeterministicException extends IllegalStateException {
  public NonDeterministicException(String message, Throwable cause) {
    super(message, cause);
  }

  public NonDeterministicException(String message) {
    super(message);
  }
}
