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

package io.temporal.testing;

import io.grpc.Context;
import io.temporal.api.testservice.v1.LockTimeSkippingRequest;
import io.temporal.api.testservice.v1.UnlockTimeSkippingRequest;
import io.temporal.serviceclient.TestServiceStubs;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Used to ensure that multiple TimeLockingWorkflowStubs that are blocked at the same time from
 * multiple threads execute unlock only once and the lock only once.
 */
class IdempotentTimeLocker {
  private final TestServiceStubs testServiceStubs;
  private final AtomicInteger count = new AtomicInteger(0);

  IdempotentTimeLocker(TestServiceStubs testServiceStubs) {
    this.testServiceStubs = testServiceStubs;
  }

  public void lockTimeSkipping() {
    int newCount = count.incrementAndGet();
    // perform an action only if we bring the counter to 0 (release of unlock)
    // or were the first who perform a lock
    if (newCount == 0 || newCount == 1) {
      Context.ROOT.run(
          () -> {
            // we want to ignore the gRPC deadline already existing in the context when we
            // communicate with the test server here to
            // 1. make sure that this operation is actually performed and
            // 2. more importantly, don't override and hide any underlying exceptions
            testServiceStubs
                .blockingStub()
                .lockTimeSkipping(LockTimeSkippingRequest.newBuilder().build());
          });
    }
  }

  public void unlockTimeSkipping() {
    int newCount = count.decrementAndGet();
    // perform an action only if we bring the counter to 0 (release of lock)
    // or were the first who perform an unlock
    if (newCount == 0 || newCount == -1) {
      Context.ROOT.run(
          () -> {
            // we want to ignore the gRPC deadline already existing in the context when we
            // communicate with the test server here to
            // 1. make sure that this operation is actually performed and
            // 2. more importantly, don't override and hide any underlying exceptions
            testServiceStubs
                .blockingStub()
                .unlockTimeSkipping(UnlockTimeSkippingRequest.newBuilder().build());
          });
    }
  }
}
