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

import com.google.common.reflect.TypeToken;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class SignalOrderingWorkflowTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(SignalOrderingWorkflowImpl.class).build();

  @Test
  public void testSignalOrderingWorkflow() {
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofMinutes(1))
            .setWorkflowTaskTimeout(Duration.ofSeconds(10))
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .build();
    SignalOrderingWorkflow workflowStub =
        testWorkflowRule.getWorkflowClient().newWorkflowStub(SignalOrderingWorkflow.class, options);
    WorkflowClient.start(workflowStub::run);

    // Suspend polling so that all the signals will be received in the same workflow task.
    testWorkflowRule.getTestEnvironment().getWorkerFactory().suspendPolling();

    workflowStub.signal("test1");
    workflowStub.signal("test2");
    workflowStub.signal("test3");

    testWorkflowRule.getTestEnvironment().getWorkerFactory().resumePolling();

    @SuppressWarnings("unchecked")
    List<String> result =
        WorkflowStub.fromTyped(workflowStub)
            .getResult(List.class, new TypeToken<List<String>>() {}.getType());
    List<String> expected = Arrays.asList("test1", "test2", "test3");
    Assert.assertEquals(expected, result);
  }

  @WorkflowInterface
  public interface SignalOrderingWorkflow {
    @WorkflowMethod
    List<String> run();

    @SignalMethod(name = "testSignal")
    void signal(String s);
  }

  public static class SignalOrderingWorkflowImpl implements SignalOrderingWorkflow {
    private final List<String> signals = new ArrayList<String>();

    @Override
    public List<String> run() {
      Workflow.await(() -> signals.size() == 3);
      return signals;
    }

    @Override
    public void signal(String s) {
      signals.add(s);
    }
  }
}
