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

package io.temporal.workflow.shared;

import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.CancellationScope;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflowWithCronSchedule;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;

public class TestWorkflowWithCronScheduleImpl implements TestWorkflowWithCronSchedule {

  public static final Map<String, AtomicInteger> retryCount = new ConcurrentHashMap<>();
  public static String lastCompletionResult;
  public static Optional<Exception> lastFail;

  @Override
  public String execute(String testName) {
    Logger log = Workflow.getLogger(TestWorkflowWithCronScheduleImpl.class);

    if (CancellationScope.current().isCancelRequested()) {
      log.debug("TestWorkflowWithCronScheduleImpl run canceled.");
      return null;
    }

    lastCompletionResult = Workflow.getLastCompletionResult(String.class);
    lastFail = Workflow.getPreviousRunFailure();

    AtomicInteger count = retryCount.get(testName);
    if (count == null) {
      count = new AtomicInteger();
      retryCount.put(testName, count);
    }
    int c = count.incrementAndGet();

    if (c == 3) {
      throw ApplicationFailure.newFailure("simulated error", "test");
    }

    SimpleDateFormat sdf = new SimpleDateFormat("MMM dd,yyyy HH:mm:ss.SSS");
    Date now = new Date(Workflow.currentTimeMillis());
    log.debug("TestWorkflowWithCronScheduleImpl run at " + sdf.format(now));
    return "run " + c;
  }
}
