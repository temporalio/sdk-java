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

package io.temporal.workflow.determinism;

import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestWorkflows;
import io.temporal.workflow.unsafe.WorkflowUnsafe;

public class DeterminismFailingWorkflowImpl implements TestWorkflows.TestWorkflowStringArg {

  @Override
  public void execute(String taskQueue) {
    TestActivities.VariousTestActivities activities =
        Workflow.newActivityStub(
            TestActivities.VariousTestActivities.class,
            SDKTestOptions.newActivityOptionsForTaskQueue(taskQueue));
    if (!WorkflowUnsafe.isReplaying()) {
      activities.activity1(1);
    }
  }
}
