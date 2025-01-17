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

package io.temporal.workflow.versionTests;

import static org.junit.Assert.*;

import io.temporal.activity.LocalActivityOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import io.temporal.workflow.unsafe.WorkflowUnsafe;
import java.time.Duration;
import org.junit.Rule;
import org.junit.Test;

public class GetVersionRemovalBeforeMarkerTest {
  private static boolean hasReplayed;

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestGetVersionRemovalWorkflowImpl.class)
          .setActivityImplementations(new TestActivitiesImpl())
          // Forcing a replay. Full history arrived from a normal queue causing a replay.
          .setWorkerOptions(
              WorkerOptions.newBuilder()
                  .setStickyQueueScheduleToStartTimeout(Duration.ZERO)
                  .build())
          .build();

  @Test
  public void testSideEffectAfterGetVersion() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    String result = workflowStub.execute("SideEffect");
    assertTrue(hasReplayed);
    assertEquals("side effect", result);
  }

  @Test
  public void testMutableSideEffectAfterGetVersion() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    String result = workflowStub.execute("MutableSideEffect");
    assertTrue(hasReplayed);
    assertEquals("mutable side effect", result);
  }

  @Test
  public void testGetVersionAfterGetVersion() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    String result = workflowStub.execute("GetVersion");
    assertTrue(hasReplayed);
    assertEquals("6", result);
  }

  @Test
  public void testLocalActivityAfterGetVersion() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    String result = workflowStub.execute("LocalActivity");
    assertTrue(hasReplayed);
    assertEquals("activity", result);
  }

  public static class TestGetVersionRemovalWorkflowImpl implements TestWorkflow1 {
    private final TestActivities.VariousTestActivities activities =
        Workflow.newLocalActivityStub(
            TestActivities.VariousTestActivities.class,
            LocalActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(5))
                .build());

    @Override
    public String execute(String action) {
      // Test removing a version check in replaying code with an additional thread running.
      if (!WorkflowUnsafe.isReplaying()) {
        int version = Workflow.getVersion("changeId", 1, 2);
        assertEquals(version, 2);
      } else {
        hasReplayed = true;
      }
      String result = "";
      if (action.equals("SideEffect")) {
        result = Workflow.sideEffect(String.class, () -> "side effect");
      } else if (action.equals("MutableSideEffect")) {
        result =
            Workflow.mutableSideEffect(
                "mutable-side-effect-i",
                String.class,
                (a, b) -> !a.equals(b),
                () -> "mutable side effect");
      } else if (action.equals("GetVersion")) {
        int v = Workflow.getVersion("otherChangeId", 5, 6);
        result = String.valueOf(v);
      } else if (action.equals("LocalActivity")) {
        result = activities.activity();
      }
      // Sleep to trigger at lest one more workflow task
      Workflow.sleep(Duration.ofSeconds(1));
      return result;
    }
  }
}
