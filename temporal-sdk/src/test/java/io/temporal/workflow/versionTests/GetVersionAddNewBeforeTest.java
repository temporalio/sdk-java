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

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeFalse;

import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows.NoArgsWorkflow;
import java.time.Duration;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GetVersionAddNewBeforeTest {

  private static final Logger log = LoggerFactory.getLogger(GetVersionAddNewBeforeTest.class);
  private static int versionFoo;
  private static int versionBar;

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestGetVersionWorkflowAddNewBefore.class)
          .setWorkerFactoryOptions(
              WorkerFactoryOptions.newBuilder()
                  .setWorkflowHostLocalTaskQueueScheduleToStartTimeout(Duration.ZERO)
                  .build())
          .build();

  @Test
  public void testGetVersionAddNewBefore() {
    assumeFalse("skipping for docker tests", SDKTestWorkflowRule.useExternalService);
    NoArgsWorkflow workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(NoArgsWorkflow.class);
    workflowStub.execute();
    assertEquals(1, versionFoo);
    assertEquals(Workflow.DEFAULT_VERSION, versionBar);
  }

  public static class TestGetVersionWorkflowAddNewBefore implements NoArgsWorkflow {

    @Override
    public void execute() {
      log.info("TestGetVersionWorkflow3Impl this=" + this.hashCode());
      // Test adding a version check in replay code.
      if (!Workflow.isReplaying()) {
        // The first version of the code
        assertEquals(1, Workflow.getVersion("changeFoo", Workflow.DEFAULT_VERSION, 1));
      } else {
        // The updated code
        versionFoo = Workflow.getVersion("changeFoo", Workflow.DEFAULT_VERSION, 1);
        versionBar = Workflow.getVersion("changeBar", Workflow.DEFAULT_VERSION, 1);
      }
      Workflow.sleep(1000); // forces new workflow task
    }
  }
}
