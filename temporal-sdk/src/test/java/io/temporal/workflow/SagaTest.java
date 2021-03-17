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

package io.temporal.workflow;

import io.temporal.testing.TracingWorkerInterceptor;
import io.temporal.workflow.shared.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestActivities;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class SagaTest {

  private final TestActivities.TestActivitiesImpl activitiesImpl =
      new TestActivities.TestActivitiesImpl(null);

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              WorkflowTest.TestSagaWorkflowImpl.class,
              WorkflowTest.TestMultiargsWorkflowsFuncImpl.class,
              WorkflowTest.TestCompensationWorkflowImpl.class)
          .setActivityImplementations(activitiesImpl)
          .setWorkerInterceptors(
              new TracingWorkerInterceptor(new TracingWorkerInterceptor.FilteredTrace()))
          .build();

  @Test
  public void testSaga() {
    WorkflowTest.TestSagaWorkflow sagaWorkflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(WorkflowTest.TestSagaWorkflow.class);
    sagaWorkflow.execute(testWorkflowRule.getTaskQueue(), false);
    testWorkflowRule
        .getInterceptor(TracingWorkerInterceptor.class)
        .setExpected(
            "interceptExecuteWorkflow " + SDKTestWorkflowRule.UUID_REGEXP,
            "newThread workflow-method",
            "executeActivity customActivity1",
            "activity customActivity1",
            "executeChildWorkflow TestMultiargsWorkflowsFunc",
            "interceptExecuteWorkflow " + SDKTestWorkflowRule.UUID_REGEXP,
            "newThread workflow-method",
            "executeActivity ThrowIO",
            "activity ThrowIO",
            "executeChildWorkflow TestCompensationWorkflow",
            "interceptExecuteWorkflow " + SDKTestWorkflowRule.UUID_REGEXP,
            "newThread workflow-method",
            "executeActivity Activity2",
            "activity Activity2");
  }

  @Test
  public void testSagaParallelCompensation() {
    WorkflowTest.TestSagaWorkflow sagaWorkflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(WorkflowTest.TestSagaWorkflow.class);
    sagaWorkflow.execute(testWorkflowRule.getTaskQueue(), true);
    String trace = testWorkflowRule.getInterceptor(TracingWorkerInterceptor.class).getTrace();
    Assert.assertTrue(trace, trace.contains("executeChildWorkflow TestCompensationWorkflow"));
    Assert.assertTrue(trace, trace.contains("executeActivity Activity2"));
  }
}
