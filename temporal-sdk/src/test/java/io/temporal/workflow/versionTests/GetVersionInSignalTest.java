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

package io.temporal.workflow.versionTests;

import static org.junit.Assert.assertEquals;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestWorkflows;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;

public class GetVersionInSignalTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(TestGetVersionInSignal.class).build();

  @Test
  public void testGetVersionInSignal() {
    TestWorkflows.TestSignaledWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.TestSignaledWorkflow.class);
    WorkflowClient.start(workflow::execute);

    WorkflowStub workflowStub = WorkflowStub.fromTyped(workflow);
    SDKTestWorkflowRule.waitForOKQuery(workflowStub);
    workflow.signal("done");
    String result = workflowStub.getResult(String.class);
    assertEquals("[done]", result);
  }

  /** The following test covers the scenario where getVersion call is performed inside a signal */
  public static class TestGetVersionInSignal implements TestWorkflows.TestSignaledWorkflow {

    private final List<String> signalled = new ArrayList<>();

    @Override
    public String execute() {
      Workflow.sleep(5_000);
      return signalled.toString();
    }

    @Override
    public void signal(String arg) {
      Workflow.getVersion("some-id", 1, 2);
      signalled.add(arg);
    }
  }
}
