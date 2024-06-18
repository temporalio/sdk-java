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
  public void canBuildMixedSlotSupplierTuner() {
    ResourceBasedController resourceController =
        ResourceBasedController.newSystemInfoController(
            ResourceBasedControllerOptions.newBuilder(0.5, 0.5).build());

    SlotSupplier<WorkflowSlotInfo> workflowTaskSlotSupplier = new FixedSizeSlotSupplier<>(10);
    SlotSupplier<ActivitySlotInfo> activityTaskSlotSupplier =
        new ResourceBasedSlotSupplier<>(
            resourceController, new ResourceBasedSlotOptions(1, 1000, Duration.ofMillis(50)));
    SlotSupplier<LocalActivitySlotInfo> localActivitySlotSupplier =
        new ResourceBasedSlotSupplier<>(
            resourceController, new ResourceBasedSlotOptions(1, 1000, Duration.ofMillis(50)));

    WorkerOptions.newBuilder()
        .setWorkerTuner(
            new TunerHolder(
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
        new ResourceBasedSlotSupplier<>(
            resourceController1, new ResourceBasedSlotOptions(1, 1000, Duration.ofMillis(50)));
    SlotSupplier<LocalActivitySlotInfo> localActivitySlotSupplier =
        new ResourceBasedSlotSupplier<>(
            resourceController2, new ResourceBasedSlotOptions(1, 1000, Duration.ofMillis(50)));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TunerHolder(
                workflowTaskSlotSupplier, activityTaskSlotSupplier, localActivitySlotSupplier));
  }
}
