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

import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowFailedException;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.NexusOperationFailure;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.*;
import io.temporal.workflow.shared.TestNexusServices;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class OperationFailureConversionTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestNexus.class)
          .setNexusServiceImplementation(new TestNexusServiceImpl())
          .build();

  @Test
  public void nexusOperationApplicationFailureNonRetryableFailureConversion() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    WorkflowFailedException exception =
        Assert.assertThrows(
            WorkflowFailedException.class,
            () -> workflowStub.execute("ApplicationFailureNonRetryable"));
    Assert.assertTrue(exception.getCause() instanceof NexusOperationFailure);
    NexusOperationFailure nexusFailure = (NexusOperationFailure) exception.getCause();
    Assert.assertTrue(nexusFailure.getCause() instanceof ApplicationFailure);
  }

  @Test
  public void nexusOperationWorkflowExecutionAlreadyStartedFailureConversion() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    WorkflowFailedException exception =
        Assert.assertThrows(
            WorkflowFailedException.class,
            () -> workflowStub.execute("WorkflowExecutionAlreadyStarted"));
    Assert.assertTrue(exception.getCause() instanceof NexusOperationFailure);
    NexusOperationFailure nexusFailure = (NexusOperationFailure) exception.getCause();
    Assert.assertTrue(nexusFailure.getCause() instanceof ApplicationFailure);
  }

  @Test
  public void nexusOperationApplicationFailureFailureConversion() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    WorkflowFailedException exception =
        Assert.assertThrows(
            WorkflowFailedException.class, () -> workflowStub.execute("ApplicationFailure"));
    Assert.assertTrue(exception.getCause() instanceof NexusOperationFailure);
    NexusOperationFailure nexusFailure = (NexusOperationFailure) exception.getCause();
    Assert.assertTrue(nexusFailure.getCause() instanceof ApplicationFailure);
    ApplicationFailure applicationFailure = (ApplicationFailure) nexusFailure.getCause();
    Assert.assertTrue(
        applicationFailure.getOriginalMessage().contains("exceeded invocation count"));
  }

  public static class TestNexus implements TestWorkflow1 {
    @Override
    public String execute(String testcase) {
      NexusOperationOptions options =
          NexusOperationOptions.newBuilder()
              .setScheduleToCloseTimeout(Duration.ofSeconds(5))
              .build();

      NexusServiceOptions serviceOptions =
          NexusServiceOptions.newBuilder().setOperationOptions(options).build();
      TestNexusServices.TestNexusService1 testNexusService =
          Workflow.newNexusServiceStub(TestNexusServices.TestNexusService1.class, serviceOptions);
      testNexusService.operation(testcase);
      return "fail";
    }
  }

  @ServiceImpl(service = TestNexusServices.TestNexusService1.class)
  public class TestNexusServiceImpl {
    Map<String, Integer> invocationCount = new ConcurrentHashMap<>();

    @OperationImpl
    public OperationHandler<String, String> operation() {
      return OperationHandler.sync(
          (ctx, details, name) -> {
            invocationCount.put(
                details.getRequestId(),
                invocationCount.getOrDefault(details.getRequestId(), 0) + 1);
            if (name.equals("ApplicationFailure")) {
              // Limit the number of retries to 2 to avoid overwhelming the test server
              if (invocationCount.get(details.getRequestId()) >= 2) {
                throw ApplicationFailure.newNonRetryableFailure(
                    "exceeded invocation count", "ExceededInvocationCount");
              }
              throw ApplicationFailure.newFailure("failed to call operation", "TestFailure");
            } else if (name.equals("ApplicationFailureNonRetryable")) {
              throw ApplicationFailure.newNonRetryableFailure(
                  "failed to call operation", "TestFailure");
            } else if (name.equals("WorkflowExecutionAlreadyStarted")) {
              throw new WorkflowExecutionAlreadyStarted(
                  WorkflowExecution.newBuilder().setWorkflowId("id").setRunId("runId").build(),
                  "TestWorkflow",
                  new RuntimeException("already started"));
            }
            Assert.fail();
            return "fail";
          });
    }
  }
}
