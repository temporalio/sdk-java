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

package io.temporal.worker.tuning;

import static org.junit.Assert.*;

import com.uber.m3.tally.NoopScope;
import io.temporal.internal.worker.SlotReservationData;
import io.temporal.internal.worker.TrackingSlotSupplier;
import org.junit.Test;

public class FixedSizeSlotSupplierTest {

  @Test
  public void ensureInterruptingReservationWorksWhenWaitingOnSemaphoreInQueue() throws Exception {
    FixedSizeSlotSupplier<WorkflowSlotInfo> supplier = new FixedSizeSlotSupplier<>(1);
    TrackingSlotSupplier<WorkflowSlotInfo> trackingSS =
        new TrackingSlotSupplier<>(supplier, new NoopScope());
    // Reserve one slot and don't release
    SlotPermit firstPermit =
        trackingSS.reserveSlot(new SlotReservationData("bla", "blah", "bla")).get();

    // Try to reserve another slot
    SlotSupplierFuture secondSlotFuture =
        trackingSS.reserveSlot(new SlotReservationData("bla", "blah", "bla"));
    // Try to reserve a third
    SlotSupplierFuture thirdSlotFuture =
        trackingSS.reserveSlot(new SlotReservationData("bla", "blah", "bla"));

    // Cancel second reservation & release first permit, which should allow third to be acquired
    SlotPermit maybePermit = secondSlotFuture.abortReservation();
    assertNull(maybePermit);
    trackingSS.releaseSlot(SlotReleaseReason.neverUsed(), firstPermit);

    SlotPermit p = thirdSlotFuture.get();
    assertNotNull(p);
  }
}
