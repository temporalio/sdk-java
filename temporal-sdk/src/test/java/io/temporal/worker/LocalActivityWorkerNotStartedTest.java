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

package io.temporal.worker;

import static org.junit.Assert.assertTrue;

import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;

public class LocalActivityWorkerNotStartedTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(NothingWorkflowImpl.class)
          .setWorkerOptions(WorkerOptions.newBuilder().setLocalActivityWorkerOnly(true).build())
          // Don't start the worker
          .setDoNotStart(true)
          .build();

  @Test
  public void canShutDownProperlyWhenNotStarted() {
    // Shut down the (never started) worker
    Instant shutdownTime = Instant.now();
    testWorkflowRule.getTestEnvironment().getWorkerFactory().shutdown();
    testWorkflowRule.getWorker().awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS);
    Set<Thread> threadSet = Thread.getAllStackTraces().keySet();
    for (Thread thread : threadSet) {
      if (thread.getName().contains("LocalActivitySlotSupplierQueue")) {
        throw new RuntimeException("Thread should be terminated");
      }
    }
    Duration elapsed = Duration.between(shutdownTime, Instant.now());
    // Shutdown should not have taken long
    assertTrue(elapsed.getSeconds() < 2);
  }

  @WorkflowInterface
  public interface NothingWorkflow {
    @WorkflowMethod
    void execute();
  }

  public static class NothingWorkflowImpl implements NothingWorkflow {
    @Override
    public void execute() {
      Workflow.sleep(500);
    }
  }
}
