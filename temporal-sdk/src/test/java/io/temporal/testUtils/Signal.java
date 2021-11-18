/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package io.temporal.testUtils;

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
   * @param timeout timeout in milliseconds - this will be honoured unless wait wakes up spuriously
   * @return true if signaled, false if returned by timeout
   * @throws InterruptedException on interruption of awaiting thread
   */
  public boolean waitForSignal(long timeout, TimeUnit timeUnit) throws InterruptedException {
    if (!isSignalled()) {
      synchronized (this) {
        wait(timeUnit.toMillis(timeout));
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
