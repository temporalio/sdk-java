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

import io.temporal.activity.ActivityOptions;
import io.temporal.activity.LocalActivityOptions;
import io.temporal.api.common.v1.Payload;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.context.ContextPropagator;
import io.temporal.common.converter.DataConverter;
import io.temporal.internal.testing.WorkflowTestingTest;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.slf4j.MDC;

public class ContextPropagationTest {
  private static final String TASK_QUEUE = "test-workflow";
  private TestWorkflowEnvironment testEnvironment;

  @Rule
  public TestWatcher watchman =
      new TestWatcher() {
        @Override
        protected void failed(Throwable e, Description description) {
          System.err.println(testEnvironment.getDiagnostics());
        }
      };

  @Before
  public void setUp() {
    TestEnvironmentOptions options =
        TestEnvironmentOptions.newBuilder()
            .setWorkflowClientOptions(
                WorkflowClientOptions.newBuilder()
                    .setContextPropagators(Collections.singletonList(new TestContextPropagator()))
                    .build())
            .build();
    testEnvironment = TestWorkflowEnvironment.newInstance(options);
  }

  @After
  public void tearDown() {
    testEnvironment.close();
  }

  @Test
  public void testWorkflowContextPropagation() {
    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(ContextPropagationWorkflowImpl.class);
    testEnvironment.start();
    MDC.put("test", "testing123");
    WorkflowClient client = testEnvironment.getWorkflowClient();
    WorkflowOptions options = WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build();
    TestWorkflows.TestWorkflow1 workflow =
        client.newWorkflowStub(TestWorkflows.TestWorkflow1.class, options);
    String result = workflow.execute("input1");
    assertEquals("testing123", result);
  }

  @Test
  public void testChildWorkflowContextPropagation() {
    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(
        ContextPropagationParentWorkflowImpl.class, ContextPropagationChildWorkflowImpl.class);
    testEnvironment.start();
    MDC.put("test", "testing123");
    WorkflowClient client = testEnvironment.getWorkflowClient();
    WorkflowOptions options = WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build();
    ParentWorkflow workflow = client.newWorkflowStub(ParentWorkflow.class, options);
    String result = workflow.workflow("input1");
    assertEquals("testing123testing123", result);
  }

  @Test
  public void testThreadContextPropagation() {
    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(ContextPropagationThreadWorkflowImpl.class);
    testEnvironment.start();
    MDC.put("test", "testing123");
    WorkflowClient client = testEnvironment.getWorkflowClient();
    WorkflowOptions options = WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build();
    TestWorkflows.TestWorkflow1 workflow =
        client.newWorkflowStub(TestWorkflows.TestWorkflow1.class, options);
    String result = workflow.execute("input1");
    assertEquals("asynctesting123", result);
  }

  @Test
  public void testActivityContextPropagation() {
    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(ContextPropagationActivityWorkflowImpl.class);
    worker.registerActivitiesImplementations(new ContextActivityImpl());
    testEnvironment.start();
    MDC.put("test", "testing123");
    WorkflowClient client = testEnvironment.getWorkflowClient();
    WorkflowOptions options = WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build();
    TestWorkflows.TestWorkflow1 workflow =
        client.newWorkflowStub(TestWorkflows.TestWorkflow1.class, options);
    String result = workflow.execute("input1");
    assertEquals("activitytesting123", result);
  }

  @Test
  public void testDefaultActivityContextPropagation() {
    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(DefaultContextPropagationActivityWorkflowImpl.class);
    worker.registerActivitiesImplementations(new ContextActivityImpl());
    testEnvironment.start();
    MDC.put("test", "testing123");
    WorkflowClient client = testEnvironment.getWorkflowClient();
    WorkflowOptions options = WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build();
    TestWorkflows.TestWorkflow1 workflow =
        client.newWorkflowStub(TestWorkflows.TestWorkflow1.class, options);
    String result = workflow.execute("input1");
    assertEquals("activitytesting123", result);
  }

  @Test
  public void testDefaultLocalActivityContextPropagation() {
    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(
        DefaultContextPropagationLocalActivityWorkflowImpl.class);
    worker.registerActivitiesImplementations(new ContextActivityImpl());
    testEnvironment.start();
    MDC.put("test", "testing123");
    WorkflowClient client = testEnvironment.getWorkflowClient();
    WorkflowOptions options = WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build();
    TestWorkflows.TestWorkflow1 workflow =
        client.newWorkflowStub(TestWorkflows.TestWorkflow1.class, options);
    String result = workflow.execute("input1");
    assertEquals("activitytesting123", result);
  }

  @Test
  public void testDefaultChildWorkflowContextPropagation() {
    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(
        DefaultContextPropagationParentWorkflowImpl.class,
        ContextPropagationChildWorkflowImpl.class);
    testEnvironment.start();
    MDC.put("test", "testing123");
    WorkflowClient client = testEnvironment.getWorkflowClient();
    WorkflowOptions options = WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build();
    WorkflowTestingTest.ParentWorkflow workflow =
        client.newWorkflowStub(WorkflowTestingTest.ParentWorkflow.class, options);
    String result = workflow.workflow("input1");
    assertEquals("testing123testing123", result);
  }

  @Test
  public void setupPropagatorOnWorkflowOptions() {
    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(ContextPropagationWorkflowImpl.class);
    testEnvironment.start();
    MDC.put("test", "testing123");

    WorkflowClient workflowClient = testEnvironment.getWorkflowClient();
    // don't setup context propagator on TestWorkflowEnvironment
    workflowClient =
        WorkflowClient.newInstance(
            workflowClient.getWorkflowServiceStubs(),
            workflowClient.getOptions().toBuilder().setContextPropagators(null).build());

    // and set them up on specific WorkflowOptions instead
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setContextPropagators(Collections.singletonList(new TestContextPropagator()))
            .setTaskQueue(TASK_QUEUE)
            .build();

    TestWorkflows.TestWorkflow1 workflow =
        workflowClient.newWorkflowStub(TestWorkflows.TestWorkflow1.class, options);
    String result = workflow.execute("input1");
    assertEquals("testing123", result);
  }

  @Test
  public void setupSamePropagatorInWorkflowOptionsAndClientOptions() {
    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(ContextPropagationWorkflowImpl.class);
    testEnvironment.start();
    MDC.put("test", "testing123");
    WorkflowClient client = testEnvironment.getWorkflowClient();

    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setTaskQueue(TASK_QUEUE)
            .setContextPropagators(Collections.singletonList(new TestContextPropagator()))
            .build();

    TestWorkflows.TestWorkflow1 workflow =
        client.newWorkflowStub(TestWorkflows.TestWorkflow1.class, options);
    String result = workflow.execute("input1");
    assertEquals("testing123", result);
  }

  @WorkflowInterface
  public interface ParentWorkflow {
    @WorkflowMethod
    String workflow(String input);

    @SignalMethod
    void signal(String value);
  }

  public static class ContextPropagationActivityWorkflowImpl
      implements TestWorkflows.TestWorkflow1 {
    @Override
    public String execute(String input) {
      TestActivities.TestActivity1 activity =
          Workflow.newActivityStub(
              TestActivities.TestActivity1.class,
              ActivityOptions.newBuilder().setScheduleToCloseTimeout(Duration.ofHours(1)).build());

      return activity.execute("foo");
    }
  }

  public static class DefaultContextPropagationLocalActivityWorkflowImpl
      implements TestWorkflows.TestWorkflow1 {
    @Override
    public String execute(String input) {
      LocalActivityOptions options =
          LocalActivityOptions.newBuilder()
              .setScheduleToCloseTimeout(Duration.ofSeconds(5))
              .build();
      TestActivities.TestActivity1 activity =
          Workflow.newLocalActivityStub(TestActivities.TestActivity1.class, options);
      return activity.execute("foo");
    }
  }

  public static class TestContextPropagator implements ContextPropagator {

    @Override
    public String getName() {
      return this.getClass().getName();
    }

    @Override
    public Map<String, Payload> serializeContext(Object context) {
      String testKey = (String) context;
      if (testKey != null) {
        return Collections.singletonMap(
            "test", DataConverter.getDefaultInstance().toPayload(testKey).get());
      } else {
        return Collections.emptyMap();
      }
    }

    @Override
    public Object deserializeContext(Map<String, Payload> context) {
      if (context.containsKey("test")) {
        return DataConverter.getDefaultInstance()
            .fromPayload(context.get("test"), String.class, String.class);

      } else {
        return null;
      }
    }

    @Override
    public Object getCurrentContext() {
      return MDC.get("test");
    }

    @Override
    public void setCurrentContext(Object context) {
      MDC.put("test", String.valueOf(context));
    }
  }

  public static class ContextPropagationWorkflowImpl implements TestWorkflows.TestWorkflow1 {

    @Override
    public String execute(String input) {
      // The test value should be in the MDC
      return MDC.get("test");
    }
  }

  public static class ContextPropagationParentWorkflowImpl
      implements WorkflowTestingTest.ParentWorkflow {

    @Override
    public String workflow(String input) {
      // Get the MDC value
      String mdcValue = MDC.get("test");

      // Fire up a child workflow
      TestWorkflows.TestWorkflow2 child =
          Workflow.newChildWorkflowStub(TestWorkflows.TestWorkflow2.class);

      return child.execute(mdcValue, Workflow.getInfo().getWorkflowId());
    }

    @Override
    public void signal(String value) {}
  }

  public static class ContextPropagationChildWorkflowImpl implements TestWorkflows.TestWorkflow2 {

    @Override
    public String execute(String input, String parentId) {
      String mdcValue = MDC.get("test");
      return input + mdcValue;
    }
  }

  public static class ContextPropagationThreadWorkflowImpl implements TestWorkflows.TestWorkflow1 {

    @Override
    public String execute(String input) {
      Promise<String> asyncPromise = Async.function(this::async);
      return asyncPromise.get();
    }

    private String async() {
      return "async" + MDC.get("test");
    }
  }

  public static class ContextActivityImpl implements TestActivities.TestActivity1 {
    @Override
    public String execute(String input) {
      return "activity" + MDC.get("test");
    }
  }

  public static class DefaultContextPropagationActivityWorkflowImpl
      implements TestWorkflows.TestWorkflow1 {
    @Override
    public String execute(String input) {
      ActivityOptions options =
          ActivityOptions.newBuilder().setScheduleToCloseTimeout(Duration.ofSeconds(5)).build();
      TestActivities.TestActivity1 activity =
          Workflow.newActivityStub(TestActivities.TestActivity1.class, options);
      return activity.execute("foo");
    }
  }

  public static class DefaultContextPropagationParentWorkflowImpl
      implements WorkflowTestingTest.ParentWorkflow {

    @Override
    public String workflow(String input) {
      // Get the MDC value
      String mdcValue = MDC.get("test");

      // Fire up a child workflow
      ChildWorkflowOptions options = ChildWorkflowOptions.newBuilder().build();
      TestWorkflows.TestWorkflow2 child =
          Workflow.newChildWorkflowStub(TestWorkflows.TestWorkflow2.class, options);

      return child.execute(mdcValue, Workflow.getInfo().getWorkflowId());
    }

    @Override
    public void signal(String value) {}
  }
}
