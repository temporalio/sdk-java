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

import static io.temporal.internal.history.VersionMarkerUtils.TEMPORAL_CHANGE_VERSION;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.temporal.client.WorkflowStub;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.testing.internal.TracingWorkerInterceptor;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestActivities.VariousTestActivities;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import io.temporal.workflow.unsafe.WorkflowUnsafe;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;

public class GetVersionUpsertSATest extends BaseVersionTest {

  private static boolean hasReplayed;

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              getDefaultWorkflowImplementationOptions(), TestGetVersionWorkflowImpl.class)
          .setActivityImplementations(new TestActivitiesImpl())
          // Forcing a replay. Full history arrived from a normal queue causing a replay.
          .setWorkerOptions(
              WorkerOptions.newBuilder()
                  .setStickyQueueScheduleToStartTimeout(Duration.ZERO)
                  .build())
          .build();

  @Test
  public void testGetVersion() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    String result = workflowStub.execute(testWorkflowRule.getTaskQueue());
    assertTrue(hasReplayed);
    assertEquals("activity22activity1activity1activity1", result);
    testWorkflowRule
        .getInterceptor(TracingWorkerInterceptor.class)
        .setExpected(
            "interceptExecuteWorkflow " + SDKTestWorkflowRule.UUID_REGEXP,
            "newThread workflow-method",
            "getVersion",
            "upsertTypedSearchAttributes",
            "executeActivity Activity2",
            "activity Activity2",
            "getVersion",
            "upsertTypedSearchAttributes",
            "executeActivity customActivity1",
            "activity customActivity1",
            "executeActivity customActivity1",
            "activity customActivity1",
            "sleep PT1S",
            "getVersion",
            "executeActivity customActivity1",
            "activity customActivity1");
    // Check that the TemporalChangeVersion search attribute is set to the users value, since that
    // was the last call made
    List<String> versions =
        WorkflowStub.fromTyped(workflowStub)
            .describe()
            .getTypedSearchAttributes()
            .get(TEMPORAL_CHANGE_VERSION);
    assertEquals(1, versions.size());
    assertEquals("test_change-2", versions.get(0));
  }

  public static class TestGetVersionWorkflowImpl implements TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      VariousTestActivities testActivities =
          Workflow.newActivityStub(
              VariousTestActivities.class,
              SDKTestOptions.newActivityOptionsForTaskQueue(taskQueue));

      // Test adding a version check in non-replay code.
      int version = Workflow.getVersion("test_change", Workflow.DEFAULT_VERSION, 1);
      assertEquals(version, 1);
      // Test a user manually setting the TemporalChangeVersion search attributes.
      Workflow.upsertTypedSearchAttributes(
          TEMPORAL_CHANGE_VERSION.valueSet(Collections.singletonList("test_change-1")));
      String result = testActivities.activity2("activity2", 2);

      // Test version change in non-replay code.
      version = Workflow.getVersion("test_change", 1, 2);
      assertEquals(version, 1);
      // Test a user manually setting the TemporalChangeVersion search attributes even when the SDK
      // normally wouldn't.
      // Intentionally use a value that the SDK would normally not set.
      Workflow.upsertTypedSearchAttributes(
          TEMPORAL_CHANGE_VERSION.valueSet(Collections.singletonList("test_change-2")));
      result += "activity" + testActivities.activity1(1);

      // Test adding a version check in replay code.
      if (WorkflowUnsafe.isReplaying()) {
        hasReplayed = true;
        int version2 = Workflow.getVersion("test_change_2", Workflow.DEFAULT_VERSION, 1);
        assertEquals(version2, Workflow.DEFAULT_VERSION);
      }
      result += "activity" + testActivities.activity1(1); // This is executed in non-replay mode.

      // Test get version in replay mode.
      Workflow.sleep(1000);
      version = Workflow.getVersion("test_change", 1, 2);
      assertEquals(version, 1);
      result += "activity" + testActivities.activity1(1);
      return result;
    }
  }
}
