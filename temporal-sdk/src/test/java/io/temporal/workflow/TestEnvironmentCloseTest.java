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

import static junit.framework.TestCase.assertTrue;

import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.workflow.shared.TestActivities.NoArgsActivity;
import org.junit.Test;

public class TestEnvironmentCloseTest {

  @Test
  public void testCloseNotHanging() throws InterruptedException {
    TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance();
    Worker worker = env.newWorker("WW");
    worker.registerWorkflowImplementationTypes(WW.class);
    worker.registerActivitiesImplementations(new AA());
    long start = System.currentTimeMillis();
    env.close();
    long elapsed = System.currentTimeMillis() - start;
    assertTrue(elapsed < 5000);
  }

  @WorkflowInterface
  public interface W {
    @WorkflowMethod
    void foo();

    @SignalMethod
    void signal();
  }

  public static class WW implements W {

    @Override
    public void foo() {}

    @Override
    public void signal() {}
  }

  public static class AA implements NoArgsActivity {

    @Override
    public void execute() {}
  }
}
