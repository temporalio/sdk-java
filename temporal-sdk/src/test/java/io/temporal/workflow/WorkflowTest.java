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

package io.temporal.workflow;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.CronSchedule;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestMultiargdsWorkflowFunctions;
import io.temporal.workflow.shared.TestOptions;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;

public class WorkflowTest {

  private static final Map<String, AtomicInteger> retryCount = new ConcurrentHashMap<>();
  public static String lastCompletionResult;
  static Optional<Exception> lastFail;

  @WorkflowInterface
  public interface TestWorkflowSignaled {

    @WorkflowMethod
    String execute();

    @SignalMethod(name = "testSignal")
    void signal1(String arg);
  }

  @WorkflowInterface
  public interface TestWorkflow {
    @WorkflowMethod
    void execute(ChildWorkflowCancellationType cancellationType);
  }

  @WorkflowInterface
  public interface TestChildWorkflow {
    @WorkflowMethod
    void execute();
  }

  @WorkflowInterface
  public interface QueryableWorkflow {

    @WorkflowMethod
    String execute();

    @QueryMethod
    String getState();

    @SignalMethod(name = "testSignal")
    void mySignal(String value);
  }

  @WorkflowInterface
  public interface ITestChild {

    @WorkflowMethod
    String execute(String arg, int delay);
  }

  @WorkflowInterface
  public interface ITestNamedChild {

    @WorkflowMethod(name = "namedChild")
    String execute(String arg);
  }

  @WorkflowInterface
  public interface SignalingChild {

    @WorkflowMethod
    String execute(String arg, String parentWorkflowId);
  }

  @WorkflowInterface
  public interface TestWorkflowRetry {

    @WorkflowMethod
    String execute(String testName);
  }

  @WorkflowInterface
  public interface TestWorkflowWithCronSchedule {
    @WorkflowMethod
    @CronSchedule("0 * * * *")
    String execute(String testName);
  }

  @WorkflowInterface
  public interface DeterminismFailingWorkflow {
    @WorkflowMethod
    void execute(String taskQueue);
  }

  public interface SignalQueryBase {
    @SignalMethod
    void signal(String arg);

    @QueryMethod
    String getSignal();
  }

  public static class TestChild implements ITestChild {

    @Override
    public String execute(String arg, int delay) {
      Workflow.sleep(delay);
      return arg.toUpperCase();
    }
  }

  public static class AngryChild implements ITestChild {

    @Override
    public String execute(String taskQueue, int delay) {
      TestActivities.AngryChildActivity activity =
          Workflow.newActivityStub(
              TestActivities.AngryChildActivity.class,
              ActivityOptions.newBuilder()
                  .setTaskQueue(taskQueue)
                  .setScheduleToCloseTimeout(Duration.ofSeconds(5))
                  .build());
      activity.execute();
      throw ApplicationFailure.newFailure("simulated failure", "test");
    }
  }

  public static class TestWorkflowWithCronScheduleImpl implements TestWorkflowWithCronSchedule {

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

  public static class DeterminismFailingWorkflowImpl implements DeterminismFailingWorkflow {

    @Override
    public void execute(String taskQueue) {
      TestActivities activities =
          Workflow.newActivityStub(
              TestActivities.class, TestOptions.newActivityOptionsForTaskQueue(taskQueue));
      if (!Workflow.isReplaying()) {
        activities.activity1(1);
      }
    }
  }

  public static class NonSerializableException extends RuntimeException {
    @SuppressWarnings("unused")
    private final InputStream file; // gson chokes on this field

    public NonSerializableException() {
      try {
        file = new FileInputStream(File.createTempFile("foo", "bar"));
      } catch (IOException e) {
        throw Activity.wrap(e);
      }
    }
  }

  public static class TestMultiargsWorkflowsFuncParent
      implements TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc {
    @Override
    public String func() {
      ChildWorkflowOptions workflowOptions =
          ChildWorkflowOptions.newBuilder()
              .setWorkflowRunTimeout(Duration.ofSeconds(100))
              .setWorkflowTaskTimeout(Duration.ofSeconds(60))
              .build();
      TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc2 child =
          Workflow.newChildWorkflowStub(
              TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc2.class, workflowOptions);

      Optional<String> parentWorkflowId = Workflow.getInfo().getParentWorkflowId();
      String childsParentWorkflowId = child.func2(null, 0);

      String result =
          String.format("%s - %s", parentWorkflowId.isPresent(), childsParentWorkflowId);
      return result;
    }
  }
}
