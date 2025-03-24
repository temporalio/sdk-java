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

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import javax.annotation.CheckReturnValue;
import javax.annotation.Nullable;

/**
 * Represents a future that will be completed with a {@link SlotPermit} when a slot is available.
 *
 * <p>This class exists to provide a reliable cancellation mechanism, since {@link
 * CompletableFuture} does not provide cancellations that properly propagate up the chain.
 */
public abstract class SlotSupplierFuture extends CompletableFuture<SlotPermit> {
  /**
   * Abort the reservation attempt. Direct implementations should cancel or interrupt any underlying
   * processes that are attempting to reserve a slot.
   */
  @Nullable
  @CheckReturnValue
  public abstract SlotPermit abortReservation();

  @Override
  public boolean cancel(boolean mayInterruptIfRunning) {
    throw new UnsupportedOperationException(
        "Do not call cancel on SlotSupplierFuture, use abortReservation");
  }

  /** See {@link CompletableFuture#completedFuture(Object)} */
  public static SlotSupplierFuture completedFuture(SlotPermit permit) {
    return new SlotSupplierFuture() {
      @Override
      public SlotPermit abortReservation() {
        return permit;
      }

      {
        complete(permit);
      }
    };
  }

  /**
   * Create a new {@link SlotSupplierFuture} from a {@link CompletableFuture}
   *
   * @param abortHandler The handler to call when the reservation is aborted. This should abort the
   *     furthest-upstream future, or call being waited on, in order to properly propagate
   *     cancellation downstream.
   */
  public static SlotSupplierFuture fromCompletableFuture(
      CompletableFuture<SlotPermit> future, Runnable abortHandler) {
    SlotSupplierFuture wrapper =
        new SlotSupplierFuture() {
          @Override
          public SlotPermit abortReservation() {
            // Try to force the future into an exceptional state.
            // completeExceptionally returns true only if it successfully transitions.
            boolean abortedNow =
                this.completeExceptionally(new CancellationException("Reservation aborted"));
            if (abortedNow) {
              abortHandler.run();
              future.cancel(true);
              return null;
            } else {
              // The future has already completed normally so return the permit.
              return this.join();
            }
          }
        };

    // Propagate the delegate future’s outcome to our wrapper
    future.whenComplete(
        (result, throwable) -> {
          // If our wrapper isn’t already completed (via abortReservation), complete it.
          if (!wrapper.isDone()) {
            if (throwable != null) {
              wrapper.completeExceptionally(throwable);
            } else {
              wrapper.complete(result);
            }
          }
        });

    return wrapper;
  }
}
