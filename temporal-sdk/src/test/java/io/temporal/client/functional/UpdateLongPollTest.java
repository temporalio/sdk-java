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

package io.temporal.client.functional;

import static org.junit.Assert.assertThrows;

import io.temporal.client.*;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.*;
import java.time.Duration;
import org.junit.Rule;
import org.junit.Test;

public class UpdateLongPollTest {
  private static final int HISTORY_LONG_POLL_TIMEOUT_SECONDS = 20;

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setUseTimeskipping(false)
          .setWorkflowTypes(WorkflowUpdateImpl.class)
          .build();

  @Test(timeout = 3 * HISTORY_LONG_POLL_TIMEOUT_SECONDS * 1000)
  public void testGetUpdateResults() {
    TestUpdatedWorkflow workflow = testWorkflowRule.newWorkflowStub(TestUpdatedWorkflow.class);
    WorkflowClient.start(workflow::execute);
    workflow.update(2 * HISTORY_LONG_POLL_TIMEOUT_SECONDS, false);
    workflow.close();
    WorkflowStub.fromTyped(workflow).getResult(Void.class);
  }

  @Test(timeout = 3 * HISTORY_LONG_POLL_TIMEOUT_SECONDS * 1000)
  public void testGetUpdateResultsFail() {
    TestUpdatedWorkflow workflow = testWorkflowRule.newWorkflowStub(TestUpdatedWorkflow.class);
    WorkflowClient.start(workflow::execute);
    assertThrows(
        WorkflowUpdateException.class,
        () -> workflow.update(2 * HISTORY_LONG_POLL_TIMEOUT_SECONDS, true));
    workflow.close();
    WorkflowStub.fromTyped(workflow).getResult(Void.class);
  }

  @WorkflowInterface
  public interface TestUpdatedWorkflow {

    @WorkflowMethod
    String execute();

    @UpdateMethod
    void update(int sleepSeconds, boolean failUpdate);

    @SignalMethod(name = "endWorkflow")
    void close();
  }

  public static class WorkflowUpdateImpl implements TestUpdatedWorkflow {
    CompletablePromise<Void> promise = Workflow.newPromise();

    @Override
    public String execute() {
      promise.get();
      return "done";
    }

    @Override
    public void update(int sleepSeconds, boolean failUpdate) {
      Workflow.sleep(Duration.ofSeconds(sleepSeconds));
      if (failUpdate) {
        throw ApplicationFailure.newFailure("test failure", "failure");
      }
    }

    @Override
    public void close() {
      promise.complete(null);
    }
  }
}
