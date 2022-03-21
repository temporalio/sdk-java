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
  private final AtomicInteger count = new AtomicInteger(1);

  IdempotentTimeLocker(TestServiceStubs testServiceStubs) {
    this.testServiceStubs = testServiceStubs;
  }

  public void lockTimeSkipping() {
    if (count.incrementAndGet() == 1) {
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
    if (count.decrementAndGet() == 0) {
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
