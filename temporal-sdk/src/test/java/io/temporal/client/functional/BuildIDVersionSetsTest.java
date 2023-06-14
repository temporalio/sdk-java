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

package io.temporal.client.functional;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import io.temporal.client.BuildIDOperation;
import io.temporal.client.WorkerBuildIDVersionSets;
import io.temporal.client.WorkflowClient;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import java.util.Arrays;
import org.junit.Rule;
import org.junit.Test;

public class BuildIDVersionSetsTest {
  @Rule public SDKTestWorkflowRule testWorkflowRule = SDKTestWorkflowRule.newBuilder().build();

  @Test
  public void testManipulateGraph() {
    assumeTrue(
        "Test Server doesn't support versioning yet", SDKTestWorkflowRule.useExternalService);

    String taskQueue = testWorkflowRule.getTaskQueue();
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();

    workflowClient.updateWorkerBuildIDCompatability(
        taskQueue, BuildIDOperation.newIDInNewDefaultSet("1.0"));
    workflowClient.updateWorkerBuildIDCompatability(
        taskQueue, BuildIDOperation.newIDInNewDefaultSet("2.0"));
    workflowClient.updateWorkerBuildIDCompatability(
        taskQueue, BuildIDOperation.newCompatibleVersion("1.1", "1.0"));

    WorkerBuildIDVersionSets sets = workflowClient.getWorkerBuildIDCompatability(taskQueue);
    assertEquals("2.0", sets.defaultBuildID());
    assertEquals(2, sets.allSets().size());
    assertEquals(Arrays.asList("1.0", "1.1"), sets.allSets().get(0).getBuildIDs());

    workflowClient.updateWorkerBuildIDCompatability(
        taskQueue, BuildIDOperation.promoteSetByBuildID("1.0"));
    sets = workflowClient.getWorkerBuildIDCompatability(taskQueue);
    assertEquals("1.1", sets.defaultBuildID());

    workflowClient.updateWorkerBuildIDCompatability(
        taskQueue, BuildIDOperation.promoteBuildIDWithinSet("1.0"));
    sets = workflowClient.getWorkerBuildIDCompatability(taskQueue);
    assertEquals("1.0", sets.defaultBuildID());

    workflowClient.updateWorkerBuildIDCompatability(
        taskQueue, BuildIDOperation.mergeSets("2.0", "1.0"));
    sets = workflowClient.getWorkerBuildIDCompatability(taskQueue);
    assertEquals("2.0", sets.defaultBuildID());
  }
}
