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

package io.temporal.workflow;

import static org.junit.Assert.fail;

import com.google.common.util.concurrent.UncheckedExecutionException;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.CanceledFailure;
import io.temporal.workflow.shared.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class WorkflowsWithFailedPromisesCanBeCanceledTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestCancellationForWorkflowsWithFailedPromises.class)
          .build();

  @Test
  public void workflowsWithFailedPromisesCanBeCanceled() {
    WorkflowStub client = testWorkflowRule.newUntypedWorkflowStubTimeoutOptions("TestWorkflow1");
    client.start(testWorkflowRule.getTaskQueue());
    client.cancel();

    try {
      client.getResult(String.class);
      Assert.fail("unreachable");
    } catch (WorkflowFailedException e) {
      Assert.assertTrue(e.getCause() instanceof CanceledFailure);
    }
  }

  public static class TestCancellationForWorkflowsWithFailedPromises implements TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      Async.function(
          () -> {
            throw new UncheckedExecutionException(new Exception("Oh noo!"));
          });
      Async.function(
          () -> {
            throw new UncheckedExecutionException(new Exception("Oh noo again!"));
          });
      Workflow.await(() -> false);
      fail("unreachable");
      return "done";
    }
  }
}
