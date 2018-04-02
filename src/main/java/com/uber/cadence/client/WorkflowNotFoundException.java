/*
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

package com.uber.cadence.client;

import com.uber.cadence.WorkflowExecution;
import java.util.Optional;

/**
 * Thrown when workflow with the given id is not known to the cadence service. It could be because
 * id is not correct or workflow was purged from the service after reaching its retention limit.
 */
public final class WorkflowNotFoundException extends WorkflowException {

  public WorkflowNotFoundException(
      WorkflowExecution execution, Optional<String> workflowType, String message) {
    super(message, execution, workflowType, null);
  }
}
