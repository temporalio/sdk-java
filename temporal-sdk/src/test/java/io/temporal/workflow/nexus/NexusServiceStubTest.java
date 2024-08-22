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
import java.util.Optional;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class NexusServiceStubTest extends BaseNexusTest {
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
  public void typedNexusServiceStub() {
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
      TestNexusService serviceStub =
          Workflow.newNexusServiceStub(TestNexusService.class, serviceOptions);
      // Try to call a synchronous operation in a blocking way
      String syncResult = serviceStub.sayHello1(name);
      // Try to call a synchronous operation in a non-blocking way
      Promise<String> syncPromise = Async.function(serviceStub::sayHello1, name);
      Assert.assertEquals(syncPromise.get(), syncResult);
      // Try to call a synchronous operation in a non-blocking way using a handle
      NexusOperationHandle<String> syncOpHandle =
          Workflow.startNexusOperation(serviceStub::sayHello1, name);
      Optional<String> syncOpId = syncOpHandle.getExecution().get();
      // Execution id is not present for synchronous operations
      Assert.assertFalse("Execution id should not be present", syncOpId.isPresent());
      // Result should always be completed for a synchronous operations when the Execution promise
      // is resolved
      Assert.assertTrue("Result should be completed", syncOpHandle.getResult().isCompleted());

      // Try to call an asynchronous operation in a blocking way
      serviceStub.sleep(100L);
      // Try to call an asynchronous operation in a non-blocking way
      Promise<Void> asyncPromise = Async.procedure(serviceStub::sleep, 100L);
      asyncPromise.get();
      // Try to call an asynchronous operation in a non-blocking way using a handle
      NexusOperationHandle<Void> asyncOpHandle =
          Workflow.startNexusOperation(serviceStub::sleep, 100L);
      Optional<String> asyncOpId = asyncOpHandle.getExecution().get();
      Assert.assertTrue("Execution id should be present", asyncOpId.isPresent());

      //
      serviceStub.returnString();
      //
      Promise<String> asyncStringResult = Async.function(serviceStub::returnString);
      asyncStringResult.get();
      //
      NexusOperationHandle<String> asyncStringHandle =
          Workflow.startNexusOperation(serviceStub::returnString);
      Optional<String> asyncStringId = asyncStringHandle.getExecution().get();
      Assert.assertTrue("Execution id should be present", asyncOpId.isPresent());
      Assert.assertEquals(asyncStringId.get(), asyncStringHandle.getResult().get());
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
