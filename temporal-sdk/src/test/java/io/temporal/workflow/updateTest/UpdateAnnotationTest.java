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

package io.temporal.workflow.updateTest;

import static org.junit.Assert.assertEquals;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.util.Optional;
import org.junit.Rule;
import org.junit.Test;

public class UpdateAnnotationTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(WorkflowImpl.class).build();

  @Test
  public void testUpdateOnlyInterface() {
    // Verify a stub to an interface with only @UpdateMethod can be created.
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();
    WorkflowOptions options =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
            .build();
    WorkflowTestInterface workflow =
        workflowClient.newWorkflowStub(WorkflowTestInterface.class, options);

    WorkflowExecution execution = WorkflowClient.start(workflow::execute);

    UpdateWorkflowInterface updateOnlyWorkflow =
        workflowClient.newWorkflowStub(UpdateWorkflowInterface.class, execution.getWorkflowId());
    updateOnlyWorkflow.update();

    String result =
        testWorkflowRule
            .getWorkflowClient()
            .newUntypedWorkflowStub(execution, Optional.empty())
            .getResult(String.class);
    assertEquals("success", result);
  }

  public interface UpdateWorkflowInterface {
    @UpdateMethod
    void update();
  }

  @WorkflowInterface
  public interface WorkflowTestInterface extends UpdateWorkflowInterface {
    @WorkflowMethod
    String execute();
  }

  public static class WorkflowImpl implements WorkflowTestInterface {
    boolean complete = false;

    @Override
    public String execute() {
      Workflow.await(() -> complete);
      return "success";
    }

    @Override
    public void update() {
      complete = true;
    }
  }
}
