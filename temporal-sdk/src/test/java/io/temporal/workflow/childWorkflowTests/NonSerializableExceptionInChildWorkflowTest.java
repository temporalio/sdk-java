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

package io.temporal.workflow.childWorkflowTests;

import io.temporal.failure.ChildWorkflowFailure;
import io.temporal.worker.WorkflowImplementationOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import io.temporal.workflow.shared.NonSerializableException;
import io.temporal.workflow.shared.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class NonSerializableExceptionInChildWorkflowTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              WorkflowImplementationOptions.newBuilder()
                  .setFailWorkflowExceptionTypes(NonSerializableException.class)
                  .build(),
              TestNonSerializableExceptionInChildWorkflow.class,
              NonSerializableExceptionChildWorkflowImpl.class)
          .build();

  @Test
  public void testNonSerializableExceptionInChildWorkflow() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    String result = workflowStub.execute(testWorkflowRule.getTaskQueue());
    Assert.assertTrue(result.contains("NonSerializableException"));
  }

  @WorkflowInterface
  public interface NonSerializableExceptionChildWorkflow {

    @WorkflowMethod
    String execute(String taskQueue);
  }

  public static class NonSerializableExceptionChildWorkflowImpl
      implements NonSerializableExceptionChildWorkflow {

    @Override
    public String execute(String taskQueue) {
      throw new NonSerializableException();
    }
  }

  public static class TestNonSerializableExceptionInChildWorkflow implements TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      NonSerializableExceptionChildWorkflow child =
          Workflow.newChildWorkflowStub(NonSerializableExceptionChildWorkflow.class);
      try {
        child.execute(taskQueue);
      } catch (ChildWorkflowFailure e) {
        return e.getMessage();
      }
      return "done";
    }
  }
}
