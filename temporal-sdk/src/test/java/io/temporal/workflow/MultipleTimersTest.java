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

import io.temporal.workflow.shared.SDKTestWorkflowRule;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class MultipleTimersTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(TestMultipleTimersImpl.class).build();

  @Test
  public void testMultipleTimers() {
    TestMultipleTimers workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestMultipleTimers.class);
    long result = workflowStub.execute();
    Assert.assertTrue("should be around 1 second: " + result, result < 2000);
  }

  @WorkflowInterface
  public interface TestMultipleTimers {
    @WorkflowMethod
    long execute();
  }

  public static class TestMultipleTimersImpl implements TestMultipleTimers {

    @Override
    public long execute() {
      Promise<Void> t1 = Async.procedure(() -> Workflow.sleep(Duration.ofSeconds(1)));
      Promise<Void> t2 = Async.procedure(() -> Workflow.sleep(Duration.ofSeconds(2)));
      long start = Workflow.currentTimeMillis();
      Promise.anyOf(t1, t2).get();
      long elapsed = Workflow.currentTimeMillis() - start;
      return elapsed;
    }
  }
}
