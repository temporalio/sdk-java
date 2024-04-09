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

import io.temporal.common.Experimental;

/**
 * This class is handed out by implementations of {@link SlotSupplier}. Permits are held until the
 * tasks they are associated with (if any) are finished processing, or if the reservation is no
 * longer needed. Your supplier implementation may store additional data in the permit, if desired.
 *
 * <p>When {@link SlotSupplier#releaseSlot(SlotReleaseContext)} is called, the exact same instance
 * of the permit is passed back to the supplier.
 */
@Experimental
public final class SlotPermit {
  public final Object userData;

  public SlotPermit() {
    this.userData = null;
  }

  public SlotPermit(Object userData) {
    this.userData = userData;
  }
}
