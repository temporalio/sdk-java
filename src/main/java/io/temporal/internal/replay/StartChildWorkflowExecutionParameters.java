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

package io.temporal.internal.replay;

import io.temporal.api.decision.v1.StartChildWorkflowExecutionDecisionAttributes;
import io.temporal.workflow.ChildWorkflowCancellationType;

public final class StartChildWorkflowExecutionParameters {

  private final StartChildWorkflowExecutionDecisionAttributes.Builder request;
  private final ChildWorkflowCancellationType cancellationType;

  public StartChildWorkflowExecutionParameters(
      StartChildWorkflowExecutionDecisionAttributes.Builder request,
      ChildWorkflowCancellationType cancellationType) {
    this.request = request;
    this.cancellationType = cancellationType;
  }

  public StartChildWorkflowExecutionDecisionAttributes.Builder getRequest() {
    return request;
  }

  public ChildWorkflowCancellationType getCancellationType() {
    return cancellationType;
  }
}
