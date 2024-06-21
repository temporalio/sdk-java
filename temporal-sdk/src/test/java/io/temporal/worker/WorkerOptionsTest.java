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
import static org.junit.Assert.assertThrows;

import io.temporal.worker.tuning.*;
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

    WorkerOptions.newBuilder()
        .setWorkerTuner(
            new CompositeTuner(
                workflowTaskSlotSupplier, activityTaskSlotSupplier, localActivitySlotSupplier))
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

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CompositeTuner(
                workflowTaskSlotSupplier, activityTaskSlotSupplier, localActivitySlotSupplier));
  }
}
