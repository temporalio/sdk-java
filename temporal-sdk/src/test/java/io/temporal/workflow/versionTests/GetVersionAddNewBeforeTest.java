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

package io.temporal.workflow.versionTests;

import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestWorkflows;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GetVersionAddNewBeforeTest {

  private static final Logger log = LoggerFactory.getLogger(GetVersionAddNewBeforeTest.class);
  private final TestActivities.TestActivitiesImpl activitiesImpl =
      new TestActivities.TestActivitiesImpl();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestGetVersionWorkflowAddNewBefore.class)
          .setActivityImplementations(activitiesImpl)
          .build();

  @Test
  public void testGetVersionAddNewBefore() {
    Assume.assumeFalse("skipping for docker tests", SDKTestWorkflowRule.useExternalService);
    TestWorkflows.TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.TestWorkflow1.class);
    workflowStub.execute(testWorkflowRule.getTaskQueue());
  }

  public static class TestGetVersionWorkflowAddNewBefore implements TestWorkflows.TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      log.info("TestGetVersionWorkflow3Impl this=" + this.hashCode());
      // Test adding a version check in replay code.
      if (!Workflow.isReplaying()) {
        // The first version of the code
        int changeFoo = Workflow.getVersion("changeFoo", Workflow.DEFAULT_VERSION, 1);
        if (changeFoo != 1) {
          throw new IllegalStateException("Unexpected version: " + 1);
        }
      } else {
        // The updated code
        int changeBar = Workflow.getVersion("changeBar", Workflow.DEFAULT_VERSION, 1);
        if (changeBar != Workflow.DEFAULT_VERSION) {
          throw new IllegalStateException("Unexpected version: " + changeBar);
        }
        int changeFoo = Workflow.getVersion("changeFoo", Workflow.DEFAULT_VERSION, 1);
        if (changeFoo != 1) {
          throw new IllegalStateException("Unexpected version: " + changeFoo);
        }
      }
      Workflow.sleep(1000); // forces new workflow task
      return "test";
    }
  }
}
