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
 * Indicates that a workflow was forcefully terminated by an external command to Cadence service.
 */
public final class WorkflowTerminatedException extends WorkflowException {

  private final byte[] details;
  private final String identity;

  public WorkflowTerminatedException(
      WorkflowExecution execution,
      Optional<String> workflowType,
      String reason,
      String identity,
      byte[] details) {
    super("Terminated by " + identity + " for \"" + reason + "\"", execution, workflowType, null);
    this.identity = identity;
    this.details = details;
  }

  public String getIdentity() {
    return identity;
  }

  public byte[] getDetails() {
    return details;
  }
}
