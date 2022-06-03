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

package io.temporal.testing;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.worker.Worker;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

// Some test server calls make take a long time. For example, when sleep leads to triggering a lot
// of events.
// Our regular rpcTimeout is 10 seconds.
// We need to make sure that such sleeps don't throw.
// This is achieved by test service stubs initialized with rpc timeout of Long.MAX_VALUE
public class TestEnvToleratesLongTestServerCalls {
  private TestWorkflowEnvironment testEnv;
  private WorkflowClient client;
  private static final String WORKFLOW_TASK_QUEUE = "EXAMPLE";

  @Before
  public void setUp() {
    setUp(TestEnvironmentOptions.getDefaultInstance());
  }

  private void setUp(TestEnvironmentOptions options) {
    testEnv = TestWorkflowEnvironment.newInstance(options);
    Worker worker = testEnv.newWorker(WORKFLOW_TASK_QUEUE);
    client = testEnv.getWorkflowClient();
    worker.registerWorkflowImplementationTypes(HangingWorkflowImpl.class);
    testEnv.start();
  }

  @After
  public void tearDown() {
    testEnv.close();
  }

  @Test(timeout = 20_000)
  public void sleepLongerThanRpcTimeoutDoesntThrow() {
    HangingWorkflow workflow =
        client.newWorkflowStub(
            HangingWorkflow.class,
            WorkflowOptions.newBuilder().setTaskQueue(WORKFLOW_TASK_QUEUE).build());
    WorkflowClient.start(workflow::execute);
    testEnv.registerDelayedCallback(
        Duration.ofMinutes(5),
        () -> {
          try {
            // 11 seconds is more than our standard rpcTimeout of 10 seconds which may cause
            Thread.sleep(11_000);
          } catch (InterruptedException e) {
            throw new RuntimeException(e);
          }
        });
    // This sleep takes longer than our standard rpcTimeout of 10 seconds.
    testEnv.sleep(Duration.ofMinutes(50L));
  }

  @WorkflowInterface
  public interface HangingWorkflow {
    @WorkflowMethod
    void execute();
  }

  public static class HangingWorkflowImpl implements HangingWorkflow {
    @Override
    public void execute() {
      Workflow.sleep(Duration.ofMinutes(20));
    }
  }
}
