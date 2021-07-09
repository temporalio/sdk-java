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

import io.temporal.activity.ActivityOptions;
import io.temporal.common.CronSchedule;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.*;
import io.temporal.workflow.shared.TestActivities.NoArgsActivity;
import io.temporal.workflow.shared.TestActivities.VariousTestActivities;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public class TestWorkflows {

  @WorkflowInterface
  public interface NoArgsWorkflow {
    @WorkflowMethod
    void execute();
  }

  @WorkflowInterface
  public interface TestWorkflowStringArg {
    @WorkflowMethod
    void execute(String arg);
  }

  @WorkflowInterface
  public interface TestWorkflowLongArg {
    @WorkflowMethod
    void execute(long arg);
  }

  @WorkflowInterface
  public interface TestWorkflowCancellationType {
    @WorkflowMethod
    void execute(ChildWorkflowCancellationType cancellationType);
  }

  @WorkflowInterface
  public interface TestWorkflowReturnMap {
    @WorkflowMethod
    Map<String, Map<String, Duration>> execute();
  }

  @WorkflowInterface
  public interface TestWorkflowReturnString {
    @WorkflowMethod
    String execute();
  }

  @WorkflowInterface
  public interface TestWorkflow1 {
    @WorkflowMethod
    String execute(String arg);
  }

  @WorkflowInterface
  public interface TestWorkflow2 {
    @WorkflowMethod
    String execute(String arg, String arg2);
  }

  @WorkflowInterface
  public interface TestWorkflow3 {
    @WorkflowMethod
    String execute(String arg, int arg2);
  }

  @WorkflowInterface
  public interface TestWorkflowWithCronSchedule {
    @WorkflowMethod
    @CronSchedule("0 * * * *")
    String execute(String testName);
  }

  @WorkflowInterface
  public interface ITestChild {
    @WorkflowMethod
    String execute(String arg, int arg2);
  }

  @WorkflowInterface
  public interface ITestNamedChild {
    @WorkflowMethod(name = "namedChild")
    String execute(String arg);
  }

  @WorkflowInterface
  public interface TestTraceWorkflow {

    @WorkflowMethod(name = "testActivity")
    String execute(boolean useExternalService);

    @QueryMethod(name = "getTrace")
    List<String> getTrace();
  }

  @WorkflowInterface
  public interface TestSignaledWorkflow {

    @WorkflowMethod
    String execute();

    @SignalMethod(name = "testSignal")
    void signal(String arg);
  }

  @WorkflowInterface
  public interface ReceiveSignalObjectWorkflow {

    @WorkflowMethod
    String execute();

    @SignalMethod(name = "testSignal")
    void signal(MetricsTest.Signal arg);

    @SignalMethod(name = "endWorkflow")
    void close();
  }

  public interface SignalQueryBase {
    @SignalMethod
    void signal(String arg);

    @QueryMethod
    String getSignal();
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

  /** IMPLEMENTATIONS * */
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
      NoArgsActivity activity =
          Workflow.newActivityStub(
              TestActivities.NoArgsActivity.class,
              ActivityOptions.newBuilder()
                  .setTaskQueue(taskQueue)
                  .setScheduleToCloseTimeout(Duration.ofSeconds(5))
                  .build());
      activity.execute();
      throw ApplicationFailure.newFailure("simulated failure", "test");
    }
  }

  public static class TestNamedChild implements ITestNamedChild {
    @Override
    public String execute(String arg) {
      return arg.toUpperCase();
    }
  }

  public static class DeterminismFailingWorkflowImpl implements TestWorkflowStringArg {

    @Override
    public void execute(String taskQueue) {
      VariousTestActivities activities =
          Workflow.newActivityStub(
              VariousTestActivities.class, TestOptions.newActivityOptionsForTaskQueue(taskQueue));
      if (!Workflow.isReplaying()) {
        activities.activity1(1);
      }
    }
  }
}
