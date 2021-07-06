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
import io.temporal.client.WorkflowStub;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import io.temporal.workflow.shared.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows.SignalQueryBase;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class SignalAndQueryInterfaceTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(SignalQueryWorkflowAImpl.class).build();

  @Test
  public void testSignalAndQueryInterface() {
    SignalQueryWorkflowA stub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(SignalQueryWorkflowA.class);
    WorkflowExecution execution = WorkflowClient.start(stub::execute);

    SignalQueryBase signalStub =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(SignalQueryBase.class, execution.getWorkflowId());
    signalStub.signal("Hello World!");
    String result = WorkflowStub.fromTyped(stub).getResult(String.class);
    String queryResult = signalStub.getSignal();
    Assert.assertEquals("Hello World!", result);
    Assert.assertEquals(queryResult, result);
  }

  @WorkflowInterface
  public interface SignalQueryWorkflowA extends SignalQueryBase {
    @WorkflowMethod
    String execute();
  }

  public static class SignalQueryWorkflowAImpl implements SignalQueryWorkflowA {

    private String signal;

    @Override
    public void signal(String arg) {
      signal = arg;
    }

    @Override
    public String getSignal() {
      return signal;
    }

    @Override
    public String execute() {
      Workflow.await(() -> signal != null);
      return signal;
    }
  }
}
