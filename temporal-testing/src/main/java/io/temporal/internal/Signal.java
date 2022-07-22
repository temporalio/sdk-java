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

package io.temporal.internal;

import java.time.Duration;
import java.time.temporal.TemporalUnit;
import java.util.concurrent.TimeUnit;

/**
 * Represents a flag which can be set or waited for. Once signalled, threads waiting for it unblock
 * or will not be blocked if started to wait when flag is already set.
 */
public class Signal {
  private final Object signalSync = new Object();
  private boolean signalled;

  /** Set the done flag */
  public void signal() {
    synchronized (signalSync) {
      signalled = true;
    }
    synchronized (this) {
      notifyAll();
    }
  }

  /**
   * Clear the signal - doesn't notify, as nothing should be waiting for this, even if they are
   * they're waiting for it to go true
   */
  public void clearSignal() {
    synchronized (signalSync) {
      signalled = false;
    }
  }

  /**
   * Wait up to timeout for the signal
   *
   * @param timeout timeout
   * @param timeUnit unit for timeout
   * @return true if signaled, false if returned by timeout
   * @throws InterruptedException on interruption of awaiting thread
   */
  public boolean waitForSignal(long timeout, TimeUnit timeUnit) throws InterruptedException {
    return waitForSignal(timeUnit.toMillis(timeout));
  }

  /**
   * Wait up to timeout for the signal
   *
   * @param timeout timeout
   * @param timeUnit unit for timeout
   * @return true if signaled, false if returned by timeout
   * @throws InterruptedException on interruption of awaiting thread
   */
  public boolean waitForSignal(long timeout, TemporalUnit timeUnit) throws InterruptedException {
    return waitForSignal(Duration.of(timeout, timeUnit).toMillis());
  }

  /**
   * Wait up to timeout for the signal
   *
   * @param timeout timeout
   * @return true if signaled, false if returned by timeout
   * @throws InterruptedException on interruption of awaiting thread
   */
  public boolean waitForSignal(Duration timeout) throws InterruptedException {
    return waitForSignal(timeout.toMillis());
  }

  /**
   * Wait up to timeout for the signal
   *
   * @param timeoutMs timeout in milliseconds
   * @return true if signaled, false if returned by timeout
   * @throws InterruptedException on interruption of awaiting thread
   */
  public boolean waitForSignal(long timeoutMs) throws InterruptedException {
    if (!isSignalled()) {
      synchronized (this) {
        wait(timeoutMs);
      }
    }
    return isSignalled();
  }

  /**
   * Wait indefinitely for the done signal
   *
   * @throws InterruptedException on interruption of awaiting thread
   */
  public void waitForSignal() throws InterruptedException {
    // as wait can wake up spuriously, put this in a loop
    while (!isSignalled()) {
      synchronized (this) {
        wait();
      }
    }
  }

  /**
   * Peek at the signal
   *
   * @return the state of the signal
   */
  public boolean isSignalled() {
    synchronized (signalSync) {
      return signalled;
    }
  }
}
