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

package io.temporal.workflow.signalTests;

import static org.junit.Assert.assertEquals;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.*;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;

public class WarnUnfinishedHandlers {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(TestSignalWorkflowImpl.class).build();

  @Test
  public void warnOnWorkflowComplete() {
    String workflowId = UUID.randomUUID().toString();
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();
    WorkflowOptions options =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
            .setWorkflowId(workflowId)
            .build();
    WorkflowWithSignal workflow = workflowClient.newWorkflowStub(WorkflowWithSignal.class, options);
    WorkflowExecution execution = WorkflowClient.start(workflow::execute);
    // Send a bunch of signals to warn
    for (int i = 0; i < 5; i++) {
      workflow.warningHandler();
    }
    for (int i = 0; i < 5; i++) {
      workflow.nonWarningHandler();
    }
    // Try to complete the workflow
    workflow.complete(CompletionMethod.COMPLETE);
    assertEquals(0, workflow.execute());
  }

  @WorkflowInterface
  public interface WorkflowWithSignal {

    @WorkflowMethod
    int execute();

    @SignalMethod
    void warningHandler();

    @SignalMethod(unfinishedPolicy = HandlerUnfinishedPolicy.ABANDON)
    void nonWarningHandler();

    @SignalMethod
    void complete(CompletionMethod method);
  }

  public static class TestSignalWorkflowImpl implements WorkflowWithSignal {
    int handlersFinished = 0;
    CompletablePromise<CompletionMethod> promise = Workflow.newPromise();

    @Override
    public int execute() {
      CompletionMethod method = promise.get();
      if (method == CompletionMethod.COMPLETE) {
        return handlersFinished;
      } else if (method == CompletionMethod.FAIL) {
        throw ApplicationFailure.newFailure("test failure", "TestFailure");
      } else if (method == CompletionMethod.CONTINUE_AS_NEW) {
        Workflow.continueAsNew();
      }
      return handlersFinished;
    }

    @Override
    public void complete(CompletionMethod method) {
      promise.complete(method);
    }

    @Override
    public void warningHandler() {
      Workflow.await(() -> false);
      handlersFinished++;
    }

    @Override
    public void nonWarningHandler() {
      Workflow.await(() -> false);
      handlersFinished++;
    }
  }

  enum CompletionMethod {
    COMPLETE,
    FAIL,
    CONTINUE_AS_NEW,
  }
}
