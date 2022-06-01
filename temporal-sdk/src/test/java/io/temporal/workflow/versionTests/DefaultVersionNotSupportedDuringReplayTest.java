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

import io.temporal.client.WorkflowClient;
import io.temporal.internal.statemachines.UnsupportedVersion;
import io.temporal.testUtils.Signal;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflowReturnString;
import io.temporal.workflow.unsafe.WorkflowUnsafe;
import java.time.Duration;
import org.junit.Rule;
import org.junit.Test;

/**
 * Verifies a situation with a workflow is executed without versioning and after that is getting
 * replayed on a code version that doesn't support the {@link
 * io.temporal.workflow.Workflow#DEFAULT_VERSION} anymore
 */
public class DefaultVersionNotSupportedDuringReplayTest {

  private static final Signal unsupportedVersionExceptionThrown = new Signal();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestVersionNotSupportedWorkflowImpl.class)
          .build();

  @Test
  public void testVersionNotSupported() throws InterruptedException {
    TestWorkflowReturnString workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflowReturnString.class);

    WorkflowClient.start(workflowStub::execute);

    unsupportedVersionExceptionThrown.waitForSignal();
  }

  public static class TestVersionNotSupportedWorkflowImpl implements TestWorkflowReturnString {

    @Override
    public String execute() {
      if (WorkflowUnsafe.isReplaying()) {
        try {
          Workflow.getVersion("test_change", 2, 3);
        } catch (UnsupportedVersion e) {
          unsupportedVersionExceptionThrown.signal();
          throw e;
        }
      }

      Workflow.sleep(Duration.ofMillis(500));
      throw new RuntimeException(); // force replay by failing WFT
    }
  }
}
