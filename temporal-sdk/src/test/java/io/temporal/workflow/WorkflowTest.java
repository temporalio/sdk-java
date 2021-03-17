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

import static org.junit.Assert.*;

import io.temporal.activity.*;
import io.temporal.common.CronSchedule;
import io.temporal.common.MethodRetry;
import io.temporal.common.RetryOptions;
import io.temporal.failure.*;
import io.temporal.workflow.shared.*;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestMultiargdsWorkflowFunctions;
import io.temporal.workflow.shared.TestWorkflows;
import java.io.*;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;

public class WorkflowTest {

  @WorkflowInterface
  public interface TestWorkflowSignaled {

    @WorkflowMethod
    String execute();

    @SignalMethod(name = "testSignal")
    void signal1(String arg);
  }

  public interface EmptyInterface {}

  public interface UnrelatedInterface {
    void unrelatedMethod();
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
  public interface NoArgsWorkflow {
    @WorkflowMethod
    String execute();
  }

  public static class TestUntypedChildStubWorkflow implements TestWorkflows.TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      ChildWorkflowOptions workflowOptions =
          ChildWorkflowOptions.newBuilder().setTaskQueue(taskQueue).build();
      ChildWorkflowStub stubF =
          Workflow.newUntypedChildWorkflowStub("TestMultiargsWorkflowsFunc", workflowOptions);
      assertEquals("func", stubF.execute(String.class));
      // Workflow type overridden through the @WorkflowMethod.name
      ChildWorkflowStub stubF1 = Workflow.newUntypedChildWorkflowStub("func1", workflowOptions);
      assertEquals("1", stubF1.execute(String.class, "1"));
      ChildWorkflowStub stubF2 =
          Workflow.newUntypedChildWorkflowStub("TestMultiargsWorkflowsFunc2", workflowOptions);
      assertEquals("12", stubF2.execute(String.class, "1", 2));
      ChildWorkflowStub stubF3 =
          Workflow.newUntypedChildWorkflowStub("TestMultiargsWorkflowsFunc3", workflowOptions);
      assertEquals("123", stubF3.execute(String.class, "1", 2, 3));
      ChildWorkflowStub stubF4 =
          Workflow.newUntypedChildWorkflowStub("TestMultiargsWorkflowsFunc4", workflowOptions);
      assertEquals("1234", stubF4.execute(String.class, "1", 2, 3, 4));
      ChildWorkflowStub stubF5 =
          Workflow.newUntypedChildWorkflowStub("TestMultiargsWorkflowsFunc5", workflowOptions);
      assertEquals("12345", stubF5.execute(String.class, "1", 2, 3, 4, 5));
      ChildWorkflowStub stubF6 =
          Workflow.newUntypedChildWorkflowStub("TestMultiargsWorkflowsFunc6", workflowOptions);
      assertEquals("123456", stubF6.execute(String.class, "1", 2, 3, 4, 5, 6));

      ChildWorkflowStub stubP =
          Workflow.newUntypedChildWorkflowStub("TestMultiargsWorkflowsProc", workflowOptions);
      stubP.execute(Void.class);
      ChildWorkflowStub stubP1 =
          Workflow.newUntypedChildWorkflowStub("TestMultiargsWorkflowsProc1", workflowOptions);
      stubP1.execute(Void.class, "1");
      ChildWorkflowStub stubP2 =
          Workflow.newUntypedChildWorkflowStub("TestMultiargsWorkflowsProc2", workflowOptions);
      stubP2.execute(Void.class, "1", 2);
      ChildWorkflowStub stubP3 =
          Workflow.newUntypedChildWorkflowStub("TestMultiargsWorkflowsProc3", workflowOptions);
      stubP3.execute(Void.class, "1", 2, 3);
      ChildWorkflowStub stubP4 =
          Workflow.newUntypedChildWorkflowStub("TestMultiargsWorkflowsProc4", workflowOptions);
      stubP4.execute(Void.class, "1", 2, 3, 4);
      ChildWorkflowStub stubP5 =
          Workflow.newUntypedChildWorkflowStub("TestMultiargsWorkflowsProc5", workflowOptions);
      stubP5.execute(Void.class, "1", 2, 3, 4, 5);
      ChildWorkflowStub stubP6 =
          Workflow.newUntypedChildWorkflowStub("TestMultiargsWorkflowsProc6", workflowOptions);
      stubP6.execute(Void.class, "1", 2, 3, 4, 5, 6);
      return null;
    }
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

  @WorkflowInterface
  public interface SignalingChild {

    @WorkflowMethod
    String execute(String arg, String parentWorkflowId);
  }

  private static final Map<String, AtomicInteger> retryCount = new ConcurrentHashMap<>();

  @WorkflowInterface
  public interface TestWorkflowRetry {

    @WorkflowMethod
    String execute(String testName);
  }

  @WorkflowInterface
  public interface TestWorkflowRetryWithMethodRetry {

    @WorkflowMethod
    @MethodRetry(
        initialIntervalSeconds = 1,
        maximumIntervalSeconds = 1,
        maximumAttempts = 30,
        doNotRetry = "java.lang.IllegalArgumentException")
    String execute(String testName);
  }

  @WorkflowInterface
  public interface TestWorkflowWithCronSchedule {
    @WorkflowMethod
    @CronSchedule("0 * * * *")
    String execute(String testName);
  }

  public static String lastCompletionResult;
  static Optional<Exception> lastFail;

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

  public static class TestCronParentWorkflow implements TestWorkflows.TestWorkflow1 {

    private final TestWorkflowWithCronSchedule cronChild =
        Workflow.newChildWorkflowStub(TestWorkflowWithCronSchedule.class);

    @Override
    public String execute(String taskQueue) {
      return cronChild.execute(taskQueue);
    }
  }

  public interface ProcInvocationQueryable {

    @QueryMethod(name = "getTrace")
    String query();
  }

  @WorkflowInterface
  public interface TestGetAttemptWorkflowsFunc {

    @WorkflowMethod
    int func();
  }

  @WorkflowInterface
  public interface DeterminismFailingWorkflow {
    @WorkflowMethod
    void execute(String taskQueue);
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

  @ActivityInterface
  public interface NonSerializableExceptionActivity {
    void execute();
  }

  public static class NonSerializableExceptionActivityImpl
      implements NonSerializableExceptionActivity {

    @Override
    public void execute() {
      throw new NonSerializableException();
    }
  }

  @ActivityInterface
  public interface NonDeserializableArgumentsActivity {
    void execute(int arg);
  }

  public static class NonDeserializableExceptionActivityImpl
      implements NonDeserializableArgumentsActivity {

    @Override
    public void execute(int arg) {}
  }

  @WorkflowInterface
  public interface NonSerializableExceptionChildWorkflow {

    @WorkflowMethod
    String execute(String taskQueue);
  }

  @WorkflowInterface
  public interface TestLargeWorkflow {
    @WorkflowMethod
    String execute(int activityCount, String taskQueue);
  }

  @ActivityInterface
  public interface TestLargeWorkflowActivity {
    String activity();
  }

  @WorkflowInterface
  public interface WorkflowTaskTimeoutWorkflow {
    @WorkflowMethod
    String execute(String testName) throws InterruptedException;
  }

  public static class TestParallelLocalActivitiesWorkflowImpl
      implements TestWorkflows.TestWorkflow1 {
    public static final int COUNT = 100;

    @Override
    public String execute(String taskQueue) {
      TestActivities localActivities =
          Workflow.newLocalActivityStub(
              TestActivities.class, TestOptions.newLocalActivityOptions());
      List<Promise<String>> laResults = new ArrayList<>();
      Random r = Workflow.newRandom();
      for (int i = 0; i < COUNT; i++) {
        laResults.add(Async.function(localActivities::sleepActivity, (long) r.nextInt(3000), i));
      }
      Promise.allOf(laResults).get();
      return "done";
    }
  }

  public static class TestLocalActivitiesWorkflowTaskHeartbeatWorkflowImpl
      implements TestWorkflows.TestWorkflow1 {
    @Override
    public String execute(String taskQueue) {
      TestActivities localActivities =
          Workflow.newLocalActivityStub(
              TestActivities.class, TestOptions.newLocalActivityOptions());
      String result = "";
      for (int i = 0; i < 5; i++) {
        result += localActivities.sleepActivity(2000, i);
      }
      return result;
    }
  }

  public static class TestLongLocalActivityWorkflowTaskHeartbeatWorkflowImpl
      implements TestWorkflows.TestWorkflow1 {
    @Override
    public String execute(String taskQueue) {
      TestActivities localActivities =
          Workflow.newLocalActivityStub(
              TestActivities.class, TestOptions.newLocalActivityOptions());
      return localActivities.sleepActivity(5000, 123);
    }
  }

  @WorkflowInterface
  public interface TestWorkflowQuery {

    @WorkflowMethod()
    String execute(String taskQueue);

    @QueryMethod()
    String query();
  }

  public interface GreetingWorkflow {

    @WorkflowMethod
    void createGreeting(String name);
  }

  public interface GreetingActivities {
    @ActivityMethod
    String composeGreeting(String string);
  }

  static class GreetingActivitiesImpl implements GreetingActivities {
    @Override
    public String composeGreeting(String string) {
      try {
        Thread.sleep(10000);
      } catch (InterruptedException e) {
        throw new Error("Unexpected", e);
      }
      return "greetings: " + string;
    }
  }

  @WorkflowInterface
  public interface TestCompensationWorkflow {
    @WorkflowMethod
    void compensate();
  }

  public static class TestMultiargsWorkflowsFuncImpl
      implements TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc {

    @Override
    public String func() {
      return "done";
    }
  }

  public static class TestCompensationWorkflowImpl implements TestCompensationWorkflow {
    @Override
    public void compensate() {}
  }

  @WorkflowInterface
  public interface TestSagaWorkflow {
    @WorkflowMethod
    String execute(String taskQueue, boolean parallelCompensation);
  }

  public static class TestSagaWorkflowImpl implements TestSagaWorkflow {

    @Override
    public String execute(String taskQueue, boolean parallelCompensation) {
      TestActivities testActivities =
          Workflow.newActivityStub(
              TestActivities.class,
              TestOptions.newActivityOptionsForTaskQueue(taskQueue)
                  .toBuilder()
                  .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                  .build());

      ChildWorkflowOptions workflowOptions =
          ChildWorkflowOptions.newBuilder().setTaskQueue(taskQueue).build();
      TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc stubF1 =
          Workflow.newChildWorkflowStub(
              TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc.class, workflowOptions);

      Saga saga =
          new Saga(
              new Saga.Options.Builder().setParallelCompensation(parallelCompensation).build());
      try {
        testActivities.activity1(10);
        saga.addCompensation(testActivities::activity2, "compensate", -10);

        stubF1.func();

        TestCompensationWorkflow compensationWorkflow =
            Workflow.newChildWorkflowStub(TestCompensationWorkflow.class, workflowOptions);
        saga.addCompensation(compensationWorkflow::compensate);

        testActivities.throwIO();
        saga.addCompensation(
            () -> {
              throw new RuntimeException("unreachable");
            });
      } catch (Exception e) {
        saga.compensate();
      }
      return "done";
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

  public interface WorkflowBase {
    @WorkflowMethod
    String execute(String arg);
  }

  public interface SignalQueryBase {
    @SignalMethod
    void signal(String arg);

    @QueryMethod
    String getSignal();
  }
}
