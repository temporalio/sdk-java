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

package io.temporal.workflow.shared;

import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.activityTests.ActivityThrowingApplicationFailureTest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class ApplicationFailureActivity implements TestActivities.TestActivity4 {
  public static final Map<String, AtomicInteger> invocations = new ConcurrentHashMap<>();

  @Override
  public void execute(String testName, boolean retryable) {
    invocations.computeIfAbsent(testName, k -> new AtomicInteger()).incrementAndGet();
    if (retryable) {
      throw ApplicationFailure.newFailure(
          "Simulate retryable failure.",
          ActivityThrowingApplicationFailureTest.FAILURE_TYPE,
          ActivityThrowingApplicationFailureTest.FAILURE_TYPE);
    }
    throw ApplicationFailure.newNonRetryableFailure(
        "Simulate non-retryable failure.",
        ActivityThrowingApplicationFailureTest.FAILURE_TYPE,
        ActivityThrowingApplicationFailureTest.FAILURE_TYPE);
  }
}
