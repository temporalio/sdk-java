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

import static io.temporal.workflow.searchattributes.UpsertTypedSearchAttributeTest.TestUpsertSearchAttributesImpl.CUSTOM_KEYWORD_ATTR;

import io.temporal.activity.ActivityOptions;
import io.temporal.activity.LocalActivityOptions;
import io.temporal.common.CronSchedule;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.*;
import io.temporal.workflow.shared.TestActivities.NoArgsActivity;
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
  public interface TestWorkflow4 {
    @WorkflowMethod
    String execute(String arg, boolean arg2);
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

    @WorkflowMethod(name = "execute")
    String execute();

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
  public interface TestUpdatedWorkflow {

    @WorkflowMethod
    String execute();

    @UpdateMethod(name = "testUpdate")
    void update(String arg);
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

  @WorkflowInterface
  public interface WorkflowWithUpdate {

    @WorkflowMethod
    String execute();

    @QueryMethod
    String getState();

    @UpdateMethod(name = "update")
    String update(Integer index, String value);

    @UpdateValidatorMethod(updateName = "update")
    void updateValidator(Integer index, String value);

    @UpdateMethod
    void complete();

    @UpdateValidatorMethod(updateName = "complete")
    void completeValidator();
  }

  @WorkflowInterface
  public interface WorkflowWithUpdateAndSignal {

    @WorkflowMethod
    List<String> execute();

    @QueryMethod
    String getState();

    @SignalMethod
    void signal(String value);

    @UpdateMethod()
    String update(String value);

    @UpdateValidatorMethod(updateName = "update")
    void validator(String value);

    @UpdateMethod
    void complete();
  }

  @WorkflowInterface
  public interface TestWorkflowWithQuery {
    @WorkflowMethod()
    String execute();

    @QueryMethod()
    String query();
  }

  /** IMPLEMENTATIONS * */
  public static class DoNothingNoArgsWorkflow implements NoArgsWorkflow {
    @Override
    public void execute() {}
  }

  public static class DoNothingTestWorkflow1 implements TestWorkflow1 {
    @Override
    public String execute(String input) {
      return input;
    }
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
      NoArgsActivity activity =
          Workflow.newActivityStub(
              NoArgsActivity.class,
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

  public static String[] illegalCallCases = {
    "start_activity",
    "start_local_activity",
    "upsert_search_attribute",
    "start_child_workflow",
    "signal_child_workflow",
    "timer",
    "random",
    "random_uuid",
    "side_effect",
    "mutable_side_effect",
    "signal_external_handle"
  };

  // Calls that are not permitted from a read only context
  public static void illegalCalls(String testCase) {
    switch (testCase) {
      case "start_activity":
        Workflow.newActivityStub(
                TestActivities.TestActivity1.class,
                ActivityOptions.newBuilder().setScheduleToCloseTimeout(Duration.ofHours(1)).build())
            .execute("test");
        break;
      case "start_local_activity":
        Workflow.newLocalActivityStub(
                TestActivities.TestActivity1.class,
                LocalActivityOptions.newBuilder()
                    .setScheduleToStartTimeout(Duration.ofHours(1))
                    .build())
            .execute("test");
        break;
      case "upsert_search_attribute":
        Workflow.upsertTypedSearchAttributes(CUSTOM_KEYWORD_ATTR.valueSet("test value"));
        break;
      case "start_child_workflow":
        Workflow.newChildWorkflowStub(TestWorkflows.TestSignaledWorkflow.class).execute();
        break;
      case "signal_child_workflow":
        Workflow.newChildWorkflowStub(TestWorkflows.TestSignaledWorkflow.class)
            .signal("test signal");
        break;
      case "timer":
        Workflow.newTimer(Duration.ofSeconds(10));
        break;
      case "random":
        Workflow.newRandom();
        break;
      case "sleep":
        Workflow.sleep(1000);
        break;
      case "random_uuid":
        Workflow.randomUUID();
        break;
      case "side_effect":
        Workflow.sideEffect(
            void.class,
            () -> {
              return null;
            });
        break;
      case "mutable_side_effect":
        Workflow.mutableSideEffect(
            "id",
            int.class,
            (Integer i, Integer j) -> {
              return i.equals(j);
            },
            () -> {
              return 0;
            });
        break;
      case "signal_external_handle":
        Workflow.newExternalWorkflowStub(
                TestWorkflows.TestSignaledWorkflow.class, "test-workflow-id")
            .signal("test signal");
        break;
      default:
        break;
    }
  }
}
