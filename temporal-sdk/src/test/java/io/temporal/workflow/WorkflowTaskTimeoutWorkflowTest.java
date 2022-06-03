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

import io.temporal.client.WorkflowOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

public class WorkflowTaskTimeoutWorkflowTest {

  private static final Map<String, AtomicInteger> retryCount = new ConcurrentHashMap<>();

  @Rule public TestName testName = new TestName();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(WorkflowTaskTimeoutWorkflowImpl.class)
          .build();

  @Test
  public void testWorkflowTaskTimeoutWorkflow() throws InterruptedException {

    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .setWorkflowTaskTimeout(Duration.ofSeconds(1))
            .build();

    WorkflowTaskTimeoutWorkflow stub =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(WorkflowTaskTimeoutWorkflow.class, options);
    String result = stub.execute(testName.getMethodName());
    Assert.assertEquals("some result", result);
  }

  @WorkflowInterface
  public interface WorkflowTaskTimeoutWorkflow {
    @WorkflowMethod
    String execute(String testName) throws InterruptedException;
  }

  public static class WorkflowTaskTimeoutWorkflowImpl implements WorkflowTaskTimeoutWorkflow {

    @Override
    public String execute(String testName) throws InterruptedException {

      AtomicInteger count = retryCount.get(testName);
      if (count == null) {
        count = new AtomicInteger();
        retryCount.put(testName, count);
        Thread.sleep(2000);
      }

      return "some result";
    }
  }
}
