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

package io.temporal.workflow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityOptions;
import io.temporal.activity.DynamicActivity;
import io.temporal.activity.LocalActivityOptions;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.converter.EncodedValues;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;

public class DynamicWorkflowTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setActivityImplementations(new DynamicActivityImpl())
          .setDoNotStart(true)
          .build();

  public static class DynamicWorkflowImpl implements DynamicWorkflow {

    @Override
    public Object execute(EncodedValues args) {
      List<String> signals = new ArrayList<>();
      String type = Workflow.getInfo().getWorkflowType();
      Workflow.registerListener(
          (DynamicSignalHandler)
              (signalName, encodedArgs) ->
                  signals.add(signalName + "-" + encodedArgs.get(0, String.class)));
      Workflow.registerListener(
          (DynamicQueryHandler)
              (queryType, encodedArgs) ->
                  queryType
                      + "-"
                      + encodedArgs.get(0, String.class)
                      + "-"
                      + signals.get(signals.size() - 1));
      String arg0 = args.get(0, String.class);
      Boolean fail = args.get(1, Boolean.class);
      if (fail != null && fail) {
        throw ApplicationFailure.newFailure("Simulated failure", "simulated");
      }
      ActivityStub activity =
          Workflow.newUntypedActivityStub(
              ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build());
      String activityResult = activity.execute("activityType1", String.class, arg0 + "-" + type);
      ActivityStub localActivity =
          Workflow.newUntypedLocalActivityStub(
              LocalActivityOptions.newBuilder()
                  .setStartToCloseTimeout(Duration.ofSeconds(10))
                  .build());
      return localActivity.execute("activityType2", String.class, activityResult);
    }
  }

  public static class DynamicActivityImpl implements DynamicActivity {
    @Override
    public Object execute(EncodedValues args) {
      return Activity.getExecutionContext().getInfo().getActivityType()
          + "-"
          + args.get(0, String.class);
    }
  }

  @Test
  public void testDynamicWorkflow() {
    TestWorkflowEnvironment testEnvironment = testWorkflowRule.getTestEnvironment();
    testEnvironment
        .getWorkerFactory()
        .getWorker(testWorkflowRule.getTaskQueue())
        .registerWorkflowImplementationTypes(DynamicWorkflowImpl.class);
    testEnvironment.start();

    WorkflowOptions workflowOptions =
        WorkflowOptions.newBuilder().setTaskQueue(testWorkflowRule.getTaskQueue()).build();
    WorkflowStub workflow =
        testWorkflowRule.getWorkflowClient().newUntypedWorkflowStub("workflowFoo", workflowOptions);
    workflow.signalWithStart("signal1", new Object[] {"signalArg0"}, new Object[] {"startArg0"});
    String queryResult = workflow.query("query1", String.class, "queryArg0");
    assertEquals("query1-queryArg0-signal1-signalArg0", queryResult);
    String result = workflow.getResult(String.class);
    assertEquals("activityType2-activityType1-startArg0-workflowFoo", result);
  }

  @Test
  public void testDynamicWorkflowFactory() {
    testWorkflowRule.addWorkflowImplementationFactory(
        DynamicWorkflowImpl.class, DynamicWorkflowImpl::new);
    TestWorkflowEnvironment testEnvironment = testWorkflowRule.getTestEnvironment();
    testEnvironment.start();
    WorkflowStub workflow = testWorkflowRule.newUntypedWorkflowStub("workflowFoo");
    workflow.signalWithStart("signal1", new Object[] {"signalArg0"}, new Object[] {"startArg0"});
    String queryResult = workflow.query("query1", String.class, "queryArg0");
    assertEquals("query1-queryArg0-signal1-signalArg0", queryResult);
    String result = workflow.getResult(String.class);
    assertEquals("activityType2-activityType1-startArg0-workflowFoo", result);
  }

  @Test(expected = WorkflowFailedException.class)
  public void testDynamicWorkflowFailure() {
    TestWorkflowEnvironment testEnvironment = testWorkflowRule.getTestEnvironment();
    testEnvironment
        .getWorkerFactory()
        .getWorker(testWorkflowRule.getTaskQueue())
        .registerWorkflowImplementationTypes(DynamicWorkflowImpl.class);
    testEnvironment.start();

    WorkflowOptions workflowOptions =
        WorkflowOptions.newBuilder().setTaskQueue(testWorkflowRule.getTaskQueue()).build();
    WorkflowStub workflow =
        testWorkflowRule.getWorkflowClient().newUntypedWorkflowStub("workflowFoo", workflowOptions);
    workflow.start("startArg0", true /* fail */);
    workflow.getResult(String.class);
  }
}
