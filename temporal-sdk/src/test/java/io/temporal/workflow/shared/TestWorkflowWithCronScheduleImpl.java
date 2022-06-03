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
import io.temporal.workflow.CancellationScope;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflowWithCronSchedule;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;

public class TestWorkflowWithCronScheduleImpl implements TestWorkflowWithCronSchedule {

  public static final Map<String, AtomicInteger> retryCount = new ConcurrentHashMap<>();
  public static final Map<String, Map<Integer, String>> lastCompletionResults =
      new ConcurrentHashMap<>();
  public static Optional<Exception> lastFail;

  @Override
  public String execute(String testName) {
    Logger log = Workflow.getLogger(TestWorkflowWithCronScheduleImpl.class);

    if (CancellationScope.current().isCancelRequested()) {
      log.debug("TestWorkflowWithCronScheduleImpl run canceled.");
      return null;
    }

    lastFail = Workflow.getPreviousRunFailure();
    int count = retryCount.computeIfAbsent(testName, k -> new AtomicInteger()).incrementAndGet();
    lastCompletionResults
        .computeIfAbsent(testName, k -> new HashMap<>())
        .put(count, Workflow.getLastCompletionResult(String.class));

    if (count == 3) {
      throw ApplicationFailure.newFailure("simulated error", "test");
    }

    SimpleDateFormat sdf = new SimpleDateFormat("MMM dd,yyyy HH:mm:ss.SSS");
    Date now = new Date(Workflow.currentTimeMillis());
    log.debug("TestWorkflowWithCronScheduleImpl run at " + sdf.format(now));
    return "run " + count;
  }
}
