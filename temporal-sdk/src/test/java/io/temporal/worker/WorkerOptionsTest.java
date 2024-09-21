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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;

import io.temporal.worker.tuning.*;
import java.time.Duration;
import org.junit.Test;

public class WorkerOptionsTest {
  @Test
  public void build() {
    WorkerOptions.Builder builder =
        WorkerOptions.newBuilder()
            .setMaxConcurrentActivityExecutionSize(10)
            .setMaxConcurrentLocalActivityExecutionSize(11);

    verifyBuild(builder.build());
    verifyBuild(builder.validateAndBuildWithDefaults());
  }

  private void verifyBuild(WorkerOptions options) {
    assertEquals(10, options.getMaxConcurrentActivityExecutionSize());
    assertEquals(11, options.getMaxConcurrentLocalActivityExecutionSize());
  }

  @Test
  public void verifyWorkerOptionsEquality() {
    WorkerOptions w1 = WorkerOptions.newBuilder().build();
    WorkerOptions w2 = WorkerOptions.newBuilder().build();
    assertEquals(w1, w2);
  }

  @Test
  public void verifyNewBuilderFromExistingWorkerOptions() {
    WorkerOptions w1 =
        WorkerOptions.newBuilder()
            .setMaxWorkerActivitiesPerSecond(100)
            .setMaxConcurrentActivityExecutionSize(1000)
            .setMaxConcurrentWorkflowTaskExecutionSize(500)
            .setMaxConcurrentLocalActivityExecutionSize(200)
            .setMaxConcurrentNexusExecutionSize(300)
            .setWorkerTuner(mock(WorkerTuner.class))
            .setMaxTaskQueueActivitiesPerSecond(50)
            .setMaxConcurrentWorkflowTaskPollers(4)
            .setMaxConcurrentActivityTaskPollers(3)
            .setMaxConcurrentNexusTaskPollers(6)
            .setLocalActivityWorkerOnly(false)
            .setDefaultDeadlockDetectionTimeout(2)
            .setMaxHeartbeatThrottleInterval(Duration.ofSeconds(10))
            .setDefaultHeartbeatThrottleInterval(Duration.ofSeconds(7))
            .setStickyQueueScheduleToStartTimeout(Duration.ofSeconds(60))
            .setDisableEagerExecution(false)
            .setUseBuildIdForVersioning(false)
            .setBuildId("build-id")
            .setStickyTaskQueueDrainTimeout(Duration.ofSeconds(15))
            .setIdentity("worker-identity")
            .build();

    WorkerOptions w2 = WorkerOptions.newBuilder(w1).build();

    assertEquals(w1.getMaxWorkerActivitiesPerSecond(), w2.getMaxWorkerActivitiesPerSecond(), 0);
    assertEquals(
        w1.getMaxConcurrentActivityExecutionSize(), w2.getMaxConcurrentActivityExecutionSize());
    assertEquals(
        w1.getMaxConcurrentWorkflowTaskExecutionSize(),
        w2.getMaxConcurrentWorkflowTaskExecutionSize());
    assertEquals(
        w1.getMaxConcurrentLocalActivityExecutionSize(),
        w2.getMaxConcurrentLocalActivityExecutionSize());
    assertEquals(w1.getMaxConcurrentNexusExecutionSize(), w2.getMaxConcurrentNexusExecutionSize());
    assertSame(w1.getWorkerTuner(), w2.getWorkerTuner());
    assertEquals(
        w1.getMaxTaskQueueActivitiesPerSecond(), w2.getMaxTaskQueueActivitiesPerSecond(), 0);
    assertEquals(
        w1.getMaxConcurrentWorkflowTaskPollers(), w2.getMaxConcurrentWorkflowTaskPollers());
    assertEquals(
        w1.getMaxConcurrentActivityTaskPollers(), w2.getMaxConcurrentActivityTaskPollers());
    assertEquals(w1.getMaxConcurrentNexusTaskPollers(), w2.getMaxConcurrentNexusTaskPollers());
    assertEquals(w1.isLocalActivityWorkerOnly(), w2.isLocalActivityWorkerOnly());
    assertEquals(w1.getMaxHeartbeatThrottleInterval(), w2.getMaxHeartbeatThrottleInterval());
    assertEquals(
        w1.getDefaultHeartbeatThrottleInterval(), w2.getDefaultHeartbeatThrottleInterval());
    assertEquals(
        w1.getStickyQueueScheduleToStartTimeout(), w2.getStickyQueueScheduleToStartTimeout());
    assertEquals(w1.isEagerExecutionDisabled(), w2.isEagerExecutionDisabled());
    assertEquals(w1.isUsingBuildIdForVersioning(), w2.isUsingBuildIdForVersioning());
    assertEquals(w1.getBuildId(), w2.getBuildId());
    assertEquals(w1.getStickyTaskQueueDrainTimeout(), w2.getStickyTaskQueueDrainTimeout());
    assertEquals(w1.getIdentity(), w2.getIdentity());
  }

  @Test
  public void canBuildMixedSlotSupplierTuner() {
    ResourceBasedController resourceController =
        ResourceBasedController.newSystemInfoController(
            ResourceBasedControllerOptions.newBuilder(0.5, 0.5).build());

    SlotSupplier<WorkflowSlotInfo> workflowTaskSlotSupplier = new FixedSizeSlotSupplier<>(10);
    SlotSupplier<ActivitySlotInfo> activityTaskSlotSupplier =
        ResourceBasedSlotSupplier.createForActivity(
            resourceController, ResourceBasedTuner.DEFAULT_ACTIVITY_SLOT_OPTIONS);
    SlotSupplier<LocalActivitySlotInfo> localActivitySlotSupplier =
        ResourceBasedSlotSupplier.createForLocalActivity(
            resourceController, ResourceBasedTuner.DEFAULT_ACTIVITY_SLOT_OPTIONS);
    SlotSupplier<NexusSlotInfo> nexusSlotSupplier = new FixedSizeSlotSupplier<>(10);

    WorkerOptions.newBuilder()
        .setWorkerTuner(
            new CompositeTuner(
                workflowTaskSlotSupplier,
                activityTaskSlotSupplier,
                localActivitySlotSupplier,
                nexusSlotSupplier))
        .build();
  }

  @Test
  public void throwsIfResourceControllerIsNotSame() {
    ResourceBasedController resourceController1 =
        ResourceBasedController.newSystemInfoController(
            ResourceBasedControllerOptions.newBuilder(0.5, 0.5).build());
    ResourceBasedController resourceController2 =
        ResourceBasedController.newSystemInfoController(
            ResourceBasedControllerOptions.newBuilder(0.2, 0.3).build());

    SlotSupplier<WorkflowSlotInfo> workflowTaskSlotSupplier = new FixedSizeSlotSupplier<>(10);
    SlotSupplier<ActivitySlotInfo> activityTaskSlotSupplier =
        ResourceBasedSlotSupplier.createForActivity(
            resourceController1, ResourceBasedTuner.DEFAULT_ACTIVITY_SLOT_OPTIONS);
    SlotSupplier<LocalActivitySlotInfo> localActivitySlotSupplier =
        ResourceBasedSlotSupplier.createForLocalActivity(
            resourceController2, ResourceBasedTuner.DEFAULT_ACTIVITY_SLOT_OPTIONS);
    SlotSupplier<NexusSlotInfo> nexusSlotSupplier = new FixedSizeSlotSupplier<>(10);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CompositeTuner(
                workflowTaskSlotSupplier,
                activityTaskSlotSupplier,
                localActivitySlotSupplier,
                nexusSlotSupplier));
  }
}
