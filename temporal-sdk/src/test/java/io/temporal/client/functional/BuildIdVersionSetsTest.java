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
import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

import io.temporal.api.enums.v1.TaskReachability;
import io.temporal.client.*;
import io.temporal.internal.testing.WorkflowTestingTest;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerOptions;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

@SuppressWarnings("OptionalGetWithoutIsPresent")
public class BuildIdVersionSetsTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setDoNotStart(true).build();

  @Before
  public void checkRealServer() {
    assumeTrue(
        "Test Server doesn't support versioning yet", SDKTestWorkflowRule.useExternalService);
  }

  @Test
  public void testManipulateGraph() {
    String taskQueue = testWorkflowRule.getTaskQueue();
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();

    workflowClient.updateWorkerBuildIdCompatability(
        taskQueue, BuildIdOperation.newIdInNewDefaultSet("1.0"));
    workflowClient.updateWorkerBuildIdCompatability(
        taskQueue, BuildIdOperation.newIdInNewDefaultSet("2.0"));
    workflowClient.updateWorkerBuildIdCompatability(
        taskQueue, BuildIdOperation.newCompatibleVersion("1.1", "1.0"));

    WorkerBuildIdVersionSets sets = workflowClient.getWorkerBuildIdCompatability(taskQueue);
    assertEquals("2.0", sets.defaultBuildId().get());
    assertEquals(2, sets.allSets().size());
    assertEquals(Arrays.asList("1.0", "1.1"), sets.allSets().get(0).getBuildIds());

    workflowClient.updateWorkerBuildIdCompatability(
        taskQueue, BuildIdOperation.promoteSetByBuildId("1.0"));
    sets = workflowClient.getWorkerBuildIdCompatability(taskQueue);
    assertEquals("1.1", sets.defaultBuildId().get());

    workflowClient.updateWorkerBuildIdCompatability(
        taskQueue, BuildIdOperation.promoteBuildIdWithinSet("1.0"));
    sets = workflowClient.getWorkerBuildIdCompatability(taskQueue);
    assertEquals("1.0", sets.defaultBuildId().get());

    workflowClient.updateWorkerBuildIdCompatability(
        taskQueue, BuildIdOperation.mergeSets("2.0", "1.0"));
    sets = workflowClient.getWorkerBuildIdCompatability(taskQueue);
    assertEquals("2.0", sets.defaultBuildId().get());
  }

  @Test
  public void testUnreachableBuildId() {
    String taskQueue = testWorkflowRule.getTaskQueue();
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();

    String buildId = UUID.randomUUID().toString();
    WorkerTaskReachability reachability =
        workflowClient.getWorkerTaskReachability(
            Collections.singleton(buildId),
            Collections.singleton(taskQueue),
            TaskReachability.TASK_REACHABILITY_UNSPECIFIED);

    assertEquals(1, reachability.getBuildIdReachability().size());
    assumeTrue(reachability.getBuildIdReachability().containsKey(buildId));
    BuildIdReachability buildIdReachability = reachability.getBuildIdReachability().get(buildId);
    assertEquals(0, buildIdReachability.getUnretrievedTaskQueues().size());
    assertEquals(1, buildIdReachability.getTaskQueueReachability().size());
    assertEquals(0, buildIdReachability.getTaskQueueReachability().get(taskQueue).size());
  }

  @Test
  public void testReachabilityUnversionedWorker() {
    String taskQueue = testWorkflowRule.getTaskQueue();
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();

    // Start an unversioned worker
    WorkerFactory factory = WorkerFactory.newInstance(workflowClient);
    Worker worker = factory.newWorker(taskQueue);
    worker.registerWorkflowImplementationTypes(WorkflowTestingTest.EmptyWorkflowImpl.class);
    factory.start();

    WorkerTaskReachability reachability =
        workflowClient.getWorkerTaskReachability(
            Collections.singleton(""),
            Collections.singleton(taskQueue),
            TaskReachability.TASK_REACHABILITY_UNSPECIFIED);

    assertEquals(1, reachability.getBuildIdReachability().size());
    assumeTrue(reachability.getBuildIdReachability().containsKey(""));
    BuildIdReachability buildIdReachability = reachability.getBuildIdReachability().get("");
    assertEquals(0, buildIdReachability.getUnretrievedTaskQueues().size());
    assertEquals(1, buildIdReachability.getTaskQueueReachability().size());
    assertEquals(
        Arrays.asList(TaskReachability.TASK_REACHABILITY_NEW_WORKFLOWS),
        buildIdReachability.getTaskQueueReachability().get(taskQueue));
  }

  @Test
  public void testReachabilityVersionedWorker() {
    String taskQueue = testWorkflowRule.getTaskQueue();
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();
    String buildId = "1.0";

    workflowClient.updateWorkerBuildIdCompatability(
        taskQueue, BuildIdOperation.newIdInNewDefaultSet(buildId));

    // Start a versioned worker
    WorkerFactory factory = WorkerFactory.newInstance(workflowClient);
    Worker worker =
        factory.newWorker(taskQueue, WorkerOptions.newBuilder().setBuildId(buildId).build());
    worker.registerWorkflowImplementationTypes(WorkflowTestingTest.EmptyWorkflowImpl.class);
    factory.start();

    WorkerTaskReachability reachability =
        workflowClient.getWorkerTaskReachability(
            Collections.singleton(buildId),
            Collections.singleton(taskQueue),
            TaskReachability.TASK_REACHABILITY_UNSPECIFIED);

    assertEquals(1, reachability.getBuildIdReachability().size());
    assumeTrue(reachability.getBuildIdReachability().containsKey(buildId));
    BuildIdReachability buildIdReachability = reachability.getBuildIdReachability().get(buildId);
    assertEquals(0, buildIdReachability.getUnretrievedTaskQueues().size());
    assertEquals(1, buildIdReachability.getTaskQueueReachability().size());
    assertEquals(
        Arrays.asList(TaskReachability.TASK_REACHABILITY_NEW_WORKFLOWS),
        buildIdReachability.getTaskQueueReachability().get(taskQueue));
  }
}
