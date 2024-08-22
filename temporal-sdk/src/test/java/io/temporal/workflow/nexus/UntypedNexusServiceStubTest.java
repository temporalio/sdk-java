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
import io.temporal.workflow.shared.nexus.TestNexusServiceImpl;
import java.time.Duration;
import java.util.Optional;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class UntypedNexusServiceStubTest extends BaseNexusTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setUseExternalService(true)
          .setWorkflowTypes(
              TestNexus.class, TestWorkflowLongArgImpl.class, TestWorkflowReturnStringImpl.class)
          .setNexusServiceImplementation(new TestNexusServiceImpl())
          .build();

  @Override
  protected SDKTestWorkflowRule getTestWorkflowRule() {
    return testWorkflowRule;
  }

  @Test
  public void untypedNexusServiceStub() {
    TestWorkflows.TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.TestWorkflow1.class);
    String result = workflowStub.execute(testWorkflowRule.getTaskQueue());
    Assert.assertEquals("Hello, " + testWorkflowRule.getTaskQueue() + "!", result);
  }

  public static class TestNexus implements TestWorkflows.TestWorkflow1 {
    @Override
    public String execute(String name) {
      NexusOperationOptions options =
          NexusOperationOptions.newBuilder()
              .setScheduleToCloseTimeout(Duration.ofSeconds(5))
              .build();
      NexusServiceOptions serviceOptions =
          NexusServiceOptions.newBuilder()
              .setEndpoint(getEndpointName())
              .setOperationOptions(options)
              .build();
      NexusServiceStub serviceStub =
          Workflow.newUntypedNexusServiceStub("TestNexusService", serviceOptions);
      String syncResult = serviceStub.execute("sayHello1", String.class, name);

      NexusOperationHandle<String> syncOpHandle =
          serviceStub.start("sayHello1", String.class, name);
      Optional<String> syncOpId = syncOpHandle.getExecution().get();
      // Execution id is not present for synchronous operations
      if (syncOpId.isPresent()) {
        Assert.fail("Execution id is present");
      }
      // Result should always be completed for a synchronous operations when the Execution promise
      // is resolved
      if (!syncOpHandle.getResult().isCompleted()) {
        Assert.fail("Result is not completed");
      }

      Promise<Void> asyncVoidResult = serviceStub.executeAsync("sleep", Void.class, 100);
      asyncVoidResult.get();

      NexusOperationHandle<Void> asyncOpHandle = serviceStub.start("sleep", Void.class, 100);
      Optional<String> asyncOpId = asyncOpHandle.getExecution().get();
      if (!asyncOpId.isPresent()) {
        Assert.fail("Execution id is not present");
      }
      asyncOpHandle.getResult().get();

      Promise<String> asyncStringResult =
          serviceStub.executeAsync("returnString", String.class, null);
      asyncStringResult.get();

      return syncResult;
    }
  }

  public static class TestWorkflowLongArgImpl implements TestWorkflows.TestWorkflowLongArg {
    @Override
    public void execute(long arg) {
      Workflow.sleep(arg);
    }
  }

  public static class TestWorkflowReturnStringImpl
      implements TestWorkflows.TestWorkflowReturnString {
    @Override
    public String execute() {
      return Workflow.getInfo().getWorkflowId();
    }
  }
}
