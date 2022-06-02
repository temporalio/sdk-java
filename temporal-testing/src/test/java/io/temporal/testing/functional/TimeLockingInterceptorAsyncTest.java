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

package io.temporal.testing.functional;

import static org.junit.Assert.assertEquals;

import io.temporal.client.WorkflowStub;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import org.junit.Rule;
import org.junit.Test;

public class TimeLockingInterceptorAsyncTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(HangingWorkflowImpl.class).build();

  /**
   * Verifies that the first getResultAsync doesn't hold timeskipping lock and the second
   * getResultAsync returns result fast too
   */
  @Test
  public void testAsyncGetResultDoesntRetainTimeLock()
      throws ExecutionException, InterruptedException {
    HangingWorkflow typedStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(HangingWorkflow.class);
    WorkflowStub untypedStub = WorkflowStub.fromTyped(typedStub);
    untypedStub.start();
    assertEquals("done", untypedStub.getResultAsync(String.class).get());

    typedStub = testWorkflowRule.newWorkflowStubTimeoutOptions(HangingWorkflow.class);
    untypedStub = WorkflowStub.fromTyped(typedStub);
    untypedStub.start();
    assertEquals("done", untypedStub.getResultAsync(String.class).get());
  }

  @WorkflowInterface
  public interface HangingWorkflow {
    @WorkflowMethod
    String execute();
  }

  public static class HangingWorkflowImpl implements HangingWorkflow {
    @Override
    public String execute() {
      Workflow.sleep(Duration.ofSeconds(150));
      return "done";
    }
  }
}
