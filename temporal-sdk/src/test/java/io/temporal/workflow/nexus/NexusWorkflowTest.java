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

package io.temporal.workflow.nexus;

import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.*;
import io.temporal.workflow.shared.TestWorkflows;
import io.temporal.workflow.shared.nexus.TestNexusService;
import io.temporal.workflow.shared.nexus.TestNexusServiceImpl;
import java.time.Duration;
import org.junit.*;

public class NexusWorkflowTest extends BaseNexusTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setUseExternalService(true)
          .setWorkflowTypes(TestNexus.class, TestWorkflow1Impl.class)
          .setNexusServiceImplementation(new TestNexusServiceImpl())
          .build();

  @Test
  public void startWorkflowOperation() {
    TestWorkflows.TestWorkflow2 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.TestWorkflow2.class);
    String result = workflowStub.execute(testWorkflowRule.getTaskQueue(), "Nexus");
    Assert.assertEquals("Hello from workflow: NexusHello from workflow: Nexus", result);
  }

  @Override
  protected SDKTestWorkflowRule getTestWorkflowRule() {
    return testWorkflowRule;
  }

  public static class TestNexus implements TestWorkflows.TestWorkflow2 {
    @Override
    public String execute(String taskQueue, String arg2) {
      NexusOperationOptions options =
          NexusOperationOptions.newBuilder()
              .setScheduleToCloseTimeout(Duration.ofSeconds(10))
              .build();
      TestNexusService testNexusService =
          Workflow.newNexusServiceStub(
              TestNexusService.class,
              NexusServiceOptions.newBuilder()
                  .setEndpoint(getEndpointName())
                  .setOperationOptions(options)
                  .build());
      Promise<String> asyncOp = Async.function(testNexusService::runWorkflow, arg2);
      String syncOp = testNexusService.runWorkflow(arg2);
      return syncOp + asyncOp.get();
    }
  }

  public static class TestWorkflow1Impl implements TestWorkflows.TestWorkflow1 {
    @Override
    public String execute(String arg) {
      return "Hello from workflow: " + arg;
    }
  }
}
