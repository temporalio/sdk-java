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

package io.temporal.common.context;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.mock.MockSpan;
import io.opentracing.mock.MockTracer;
import io.opentracing.util.GlobalTracer;
import io.opentracing.util.ThreadLocalScopeManager;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.common.v1.Payload;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.converter.DataConverter;
import io.temporal.failure.ApplicationFailure;
import io.temporal.internal.testing.WorkflowTestingTest;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.workflow.Async;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.MDC;

public class ContextTest {

  private static final String TASK_QUEUE = "test-workflow";

  private TestWorkflowEnvironment testEnvironment;
  private MockTracer mockTracer =
      new MockTracer(new ThreadLocalScopeManager(), MockTracer.Propagator.TEXT_MAP);

  @Before
  public void setUp() {
    TestEnvironmentOptions options =
        TestEnvironmentOptions.newBuilder()
            .setWorkflowClientOptions(
                WorkflowClientOptions.newBuilder()
                    .setContextPropagators(
                        Arrays.asList(
                            new TestContextPropagator(), new OpenTracingContextPropagator()))
                    .build())
            .build();
    testEnvironment = TestWorkflowEnvironment.newInstance(options);
    GlobalTracer.registerIfAbsent(mockTracer);
  }

  @After
  public void tearDown() {
    testEnvironment.close();
    mockTracer.reset();
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

  public static class ContextPropagationWorkflowImpl implements WorkflowTestingTest.TestWorkflow {

    @Override
    public String workflow1(String input) {
      // The test value should be in the MDC
      return MDC.get("test");
    }
  }

  @Test
  public void testWorkflowContextPropagation() {
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
    WorkflowTestingTest.TestWorkflow workflow =
        client.newWorkflowStub(WorkflowTestingTest.TestWorkflow.class, options);
    String result = workflow.workflow1("input1");
    assertEquals("testing123", result);
  }

  public static class ContextPropagationParentWorkflowImpl
      implements WorkflowTestingTest.ParentWorkflow {

    @Override
    public String workflow(String input) {
      // Get the MDC value
      String mdcValue = MDC.get("test");

      // Fire up a child workflow
      ChildWorkflowOptions options =
          ChildWorkflowOptions.newBuilder()
              .setContextPropagators(Collections.singletonList(new TestContextPropagator()))
              .build();
      WorkflowTestingTest.ChildWorkflow child =
          Workflow.newChildWorkflowStub(WorkflowTestingTest.ChildWorkflow.class, options);

      String result = child.workflow(mdcValue, Workflow.getInfo().getWorkflowId());
      return result;
    }

    @Override
    public void signal(String value) {}
  }

  public static class ContextPropagationChildWorkflowImpl
      implements WorkflowTestingTest.ChildWorkflow {

    @Override
    public String workflow(String input, String parentId) {
      String mdcValue = MDC.get("test");
      return input + mdcValue;
    }
  }

  @Test
  public void testChildWorkflowContextPropagation() {
    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(
        ContextPropagationParentWorkflowImpl.class, ContextPropagationChildWorkflowImpl.class);
    testEnvironment.start();
    MDC.put("test", "testing123");
    WorkflowClient client = testEnvironment.getWorkflowClient();
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setTaskQueue(TASK_QUEUE)
            .setContextPropagators(Collections.singletonList(new TestContextPropagator()))
            .build();
    WorkflowTestingTest.ParentWorkflow workflow =
        client.newWorkflowStub(WorkflowTestingTest.ParentWorkflow.class, options);
    String result = workflow.workflow("input1");
    assertEquals("testing123testing123", result);
  }

  public static class ContextPropagationThreadWorkflowImpl
      implements WorkflowTestingTest.TestWorkflow {

    @Override
    public String workflow1(String input) {
      Promise<String> asyncPromise = Async.function(this::async);
      return asyncPromise.get();
    }

    private String async() {
      return "async" + MDC.get("test");
    }
  }

  @Test
  public void testThreadContextPropagation() {
    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(ContextPropagationThreadWorkflowImpl.class);
    testEnvironment.start();
    MDC.put("test", "testing123");
    WorkflowClient client = testEnvironment.getWorkflowClient();
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setContextPropagators(Collections.singletonList(new TestContextPropagator()))
            .setTaskQueue(TASK_QUEUE)
            .build();
    WorkflowTestingTest.TestWorkflow workflow =
        client.newWorkflowStub(WorkflowTestingTest.TestWorkflow.class, options);
    String result = workflow.workflow1("input1");
    assertEquals("asynctesting123", result);
  }

  public static class ContextActivityImpl implements WorkflowTestingTest.TestActivity {
    @Override
    public String activity1(String input) {
      return "activity" + MDC.get("test");
    }
  }

  public static class ContextPropagationActivityWorkflowImpl
      implements WorkflowTestingTest.TestWorkflow {
    @Override
    public String workflow1(String input) {
      ActivityOptions options =
          ActivityOptions.newBuilder()
              .setScheduleToCloseTimeout(Duration.ofSeconds(5))
              .setContextPropagators(Collections.singletonList(new TestContextPropagator()))
              .build();
      WorkflowTestingTest.TestActivity activity =
          Workflow.newActivityStub(
              WorkflowTestingTest.TestActivity.class,
              ActivityOptions.newBuilder().setScheduleToCloseTimeout(Duration.ofHours(1)).build());

      return activity.activity1("foo");
    }
  }

  @Test
  public void testActivityContextPropagation() {
    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(ContextPropagationActivityWorkflowImpl.class);
    worker.registerActivitiesImplementations(new ContextActivityImpl());
    testEnvironment.start();
    MDC.put("test", "testing123");
    WorkflowClient client = testEnvironment.getWorkflowClient();
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setTaskQueue(TASK_QUEUE)
            .setContextPropagators(Collections.singletonList(new TestContextPropagator()))
            .build();
    WorkflowTestingTest.TestWorkflow workflow =
        client.newWorkflowStub(WorkflowTestingTest.TestWorkflow.class, options);
    String result = workflow.workflow1("input1");
    assertEquals("activitytesting123", result);
  }

  public static class DefaultContextPropagationActivityWorkflowImpl
      implements WorkflowTestingTest.TestWorkflow {
    @Override
    public String workflow1(String input) {
      ActivityOptions options =
          ActivityOptions.newBuilder().setScheduleToCloseTimeout(Duration.ofSeconds(5)).build();
      WorkflowTestingTest.TestActivity activity =
          Workflow.newActivityStub(WorkflowTestingTest.TestActivity.class, options);
      return activity.activity1("foo");
    }
  }

  @Test
  public void testDefaultActivityContextPropagation() {
    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(DefaultContextPropagationActivityWorkflowImpl.class);
    worker.registerActivitiesImplementations(new ContextActivityImpl());
    testEnvironment.start();
    MDC.put("test", "testing123");
    WorkflowClient client = testEnvironment.getWorkflowClient();
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setTaskQueue(TASK_QUEUE)
            .setContextPropagators(Collections.singletonList(new TestContextPropagator()))
            .build();
    WorkflowTestingTest.TestWorkflow workflow =
        client.newWorkflowStub(WorkflowTestingTest.TestWorkflow.class, options);
    String result = workflow.workflow1("input1");
    assertEquals("activitytesting123", result);
  }

  public static class DefaultContextPropagationParentWorkflowImpl
      implements WorkflowTestingTest.ParentWorkflow {

    @Override
    public String workflow(String input) {
      // Get the MDC value
      String mdcValue = MDC.get("test");

      // Fire up a child workflow
      ChildWorkflowOptions options = ChildWorkflowOptions.newBuilder().build();
      WorkflowTestingTest.ChildWorkflow child =
          Workflow.newChildWorkflowStub(WorkflowTestingTest.ChildWorkflow.class, options);

      String result = child.workflow(mdcValue, Workflow.getInfo().getWorkflowId());
      return result;
    }

    @Override
    public void signal(String value) {}
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
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setTaskQueue(TASK_QUEUE)
            .setContextPropagators(Collections.singletonList(new TestContextPropagator()))
            .build();
    WorkflowTestingTest.ParentWorkflow workflow =
        client.newWorkflowStub(WorkflowTestingTest.ParentWorkflow.class, options);
    String result = workflow.workflow("input1");
    assertEquals("testing123testing123", result);
  }

  public static class OpenTracingContextPropagationWorkflowImpl
      implements WorkflowTestingTest.TestWorkflow {
    @Override
    public String workflow1(String input) {
      Tracer tracer = GlobalTracer.get();
      Span activeSpan = tracer.scopeManager().activeSpan();
      MockSpan mockSpan = (MockSpan) activeSpan;
      assertNotNull(activeSpan);
      assertEquals("TestWorkflow", mockSpan.tags().get("resource.name"));
      assertNotEquals(0, mockSpan.parentId());
      if ("fail".equals(input)) {
        throw ApplicationFailure.newFailure("fail", "fail");
      } else {
        return activeSpan.getBaggageItem("foo");
      }
    }
  }

  public static class OpenTracingContextPropagationWithActivityWorkflowImpl
      implements WorkflowTestingTest.TestWorkflow {
    @Override
    public String workflow1(String input) {
      ActivityOptions options =
          ActivityOptions.newBuilder().setScheduleToCloseTimeout(Duration.ofSeconds(5)).build();
      WorkflowTestingTest.TestActivity activity =
          Workflow.newActivityStub(WorkflowTestingTest.TestActivity.class, options);
      return activity.activity1(input);
    }
  }

  public static class OpenTracingContextPropagationActivityImpl
      implements WorkflowTestingTest.TestActivity {

    @Override
    public String activity1(String input) {
      Tracer tracer = GlobalTracer.get();
      Span activeSpan = tracer.scopeManager().activeSpan();
      MockSpan mockSpan = (MockSpan) activeSpan;
      assertNotNull(activeSpan);
      assertEquals("Activity1", mockSpan.tags().get("resource.name"));
      assertNotEquals(0, mockSpan.parentId());
      if ("fail".equals(input)) {
        throw ApplicationFailure.newFailure("fail", "fail");
      } else {
        return activeSpan.getBaggageItem("foo");
      }
    }
  }

  @Test
  public void testOpenTracingContextPropagation() {
    Tracer tracer = GlobalTracer.get();
    Span span = tracer.buildSpan("testContextPropagationSuccess").start();

    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(OpenTracingContextPropagationWorkflowImpl.class);
    testEnvironment.start();
    WorkflowClient client = testEnvironment.getWorkflowClient();
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setTaskQueue(TASK_QUEUE)
            .setContextPropagators(
                Arrays.asList(new TestContextPropagator(), new OpenTracingContextPropagator()))
            .build();

    try (Scope scope = tracer.scopeManager().activate(span)) {

      Span activeSpan = tracer.scopeManager().activeSpan();
      activeSpan.setBaggageItem("foo", "bar");

      WorkflowTestingTest.TestWorkflow workflow =
          client.newWorkflowStub(WorkflowTestingTest.TestWorkflow.class, options);
      assertEquals("bar", workflow.workflow1("input1"));

    } finally {
      span.finish();
    }
  }

  @Test
  public void testOpenTracingContextPropagationWithFailure() {
    Tracer tracer = GlobalTracer.get();
    Span span = tracer.buildSpan("testContextPropagationFailure").start();

    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(OpenTracingContextPropagationWorkflowImpl.class);
    testEnvironment.start();
    WorkflowClient client = testEnvironment.getWorkflowClient();
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setTaskQueue(TASK_QUEUE)
            .setContextPropagators(
                Arrays.asList(new TestContextPropagator(), new OpenTracingContextPropagator()))
            .build();

    try (Scope scope = tracer.scopeManager().activate(span)) {

      Span activeSpan = tracer.scopeManager().activeSpan();
      activeSpan.setBaggageItem("foo", "bar");

      WorkflowTestingTest.TestWorkflow workflow =
          client.newWorkflowStub(WorkflowTestingTest.TestWorkflow.class, options);
      try {
        workflow.workflow1("fail");
        fail("Unreachable");
      } catch (ApplicationFailure e) {
        // Expected
        assertEquals("fail", e.getMessage());
      } catch (Exception e) {
        e.printStackTrace();
      }

    } finally {
      span.finish();
    }
  }

  @Test
  public void testOpenTracingContextPropagationToActivity() {
    Tracer tracer = GlobalTracer.get();
    Span span = tracer.buildSpan("testContextPropagationWithActivity").start();

    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(
        OpenTracingContextPropagationWithActivityWorkflowImpl.class);
    worker.registerActivitiesImplementations(new OpenTracingContextPropagationActivityImpl());
    testEnvironment.start();
    WorkflowClient client = testEnvironment.getWorkflowClient();
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setTaskQueue(TASK_QUEUE)
            .setContextPropagators(
                Arrays.asList(new TestContextPropagator(), new OpenTracingContextPropagator()))
            .build();

    try (Scope scope = tracer.scopeManager().activate(span)) {

      Span activeSpan = tracer.scopeManager().activeSpan();
      activeSpan.setBaggageItem("foo", "bar");

      WorkflowTestingTest.TestWorkflow workflow =
          client.newWorkflowStub(WorkflowTestingTest.TestWorkflow.class, options);
      assertEquals("bar", workflow.workflow1("input1"));

    } finally {
      span.finish();
    }
  }
}
