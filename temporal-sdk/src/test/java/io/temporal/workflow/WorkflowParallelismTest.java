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

package io.temporal.workflow;

public class WorkflowParallelismTest {
  //  @Rule
  //  public SDKTestWorkflowRule testWorkflowRule =
  //      SDKTestWorkflowRule.newBuilder()
  //          .setUseExternalService(true)
  //          .setWorkflowTypes(TestSignaledWorkflowImpl.class)
  //          .setWorkerFactoryOptions(
  //              WorkerFactoryOptions.newBuilder().setEnableVirtualThreads(true).build())
  //          .build();
  //
  //  @Test
  //  public void testWorkflowWithLotsOfThreads() {
  //    assumeTrue("Skip on JDK < 21", false);
  //    String[] javaVersionElements = System.getProperty("java.version").split("\\.");
  //    int javaVersion = Integer.parseInt(javaVersionElements[1]);
  //    SignaledWorkflow workflowStub =
  //        testWorkflowRule
  //            .getWorkflowClient()
  //            .newWorkflowStub(
  //                SignaledWorkflow.class,
  //                SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()));
  //
  //    WorkflowClient.start(workflowStub::execute);
  //
  //    for (int i = 0; i < 700; i++) {
  //      workflowStub.signal();
  //    }
  //    workflowStub.unblock();
  //
  //    Assert.assertEquals("result=2, 100", workflowStub.execute());
  //  }

  public static class TestSignaledWorkflowImpl implements SignaledWorkflow {
    private int signalCount = 0;
    private boolean unblocked = false;

    @Override
    public String execute() {
      Workflow.await(() -> unblocked);
      return String.valueOf(signalCount);
    }

    @Override
    public void signal() {
      signalCount++;
      Workflow.await(() -> unblocked);
    }

    @Override
    public void unblock() {
      unblocked = true;
    }
  }

  @WorkflowInterface
  public interface SignaledWorkflow {

    @WorkflowMethod
    String execute();

    @SignalMethod
    void signal();

    @SignalMethod
    void unblock();
  }
}
