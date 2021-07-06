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

package io.temporal.workflow.signalTests;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowQueryException;
import io.temporal.testing.TracingWorkerInterceptor;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import io.temporal.workflow.shared.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestOptions;
import io.temporal.workflow.shared.TestWorkflows.SignalQueryBase;
import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class SignalAndQueryListenerTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestSignalAndQueryListenerWorkflowImpl.class)
          .build();

  @Test
  public void testSignalAndQueryListener() {
    WorkflowOptions options =
        TestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue());
    TestSignalAndQueryListenerWorkflow stub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestSignalAndQueryListenerWorkflow.class);
    WorkflowExecution execution = WorkflowClient.start(stub::execute);

    SignalQueryBase signalStub =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(SignalQueryBase.class, execution.getWorkflowId());
    // Send signals before listener is registered to test signal buffering
    signalStub.signal("a");
    signalStub.signal("b");
    try {
      signalStub.getSignal();
      Assert.fail("unreachable"); // as not listener is not registered yet
    } catch (WorkflowQueryException e) {
      Assert.assertTrue(e.getCause().getMessage().contains("Unknown query type: getSignal"));
    }
    stub.register();
    while (true) {
      try {
        Assert.assertEquals("a, b", signalStub.getSignal());
        break;
      } catch (WorkflowQueryException e) {
        Assert.assertTrue(e.getMessage().contains("Unknown query type: getSignal"));
      }
    }
    testWorkflowRule
        .getInterceptor(TracingWorkerInterceptor.class)
        .setExpected(
            "interceptExecuteWorkflow " + SDKTestWorkflowRule.UUID_REGEXP,
            "registerSignalHandlers register",
            "newThread workflow-method",
            "await await",
            "handleSignal register",
            "registerQuery getSignal",
            "registerSignalHandlers signal",
            "handleSignal signal",
            "handleSignal signal",
            "handleQuery getSignal",
            "query getSignal");
  }

  @WorkflowInterface
  public interface TestSignalAndQueryListenerWorkflow {
    @WorkflowMethod
    void execute();

    @SignalMethod
    void register();
  }

  public static class TestSignalAndQueryListenerWorkflowImpl
      implements TestSignalAndQueryListenerWorkflow {

    private final List<String> signals = new ArrayList<String>();
    private boolean register;

    @Override
    public void execute() {
      Workflow.await(() -> register);
      Workflow.registerListener(
          new SignalQueryBase() {

            @Override
            public void signal(String arg) {
              signals.add(arg);
            }

            @Override
            public String getSignal() {
              return String.join(", ", signals);
            }
          });
    }

    @Override
    public void register() {
      register = true;
    }
  }
}
