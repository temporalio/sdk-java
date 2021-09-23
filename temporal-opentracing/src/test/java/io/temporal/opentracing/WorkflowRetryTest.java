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

package io.temporal.opentracing;

import static org.junit.Assert.assertEquals;

import io.opentracing.Scope;
import io.opentracing.mock.MockSpan;
import io.opentracing.mock.MockTracer;
import io.opentracing.util.GlobalTracer;
import io.opentracing.util.ThreadLocalScopeManager;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class WorkflowRetryTest {

  private final MockTracer mockTracer =
      new MockTracer(new ThreadLocalScopeManager(), MockTracer.Propagator.TEXT_MAP);

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowClientOptions(
              WorkflowClientOptions.newBuilder()
                  .setInterceptors(new OpenTracingClientInterceptor())
                  .validateAndBuildWithDefaults())
          .setWorkerFactoryOptions(
              WorkerFactoryOptions.newBuilder()
                  .setWorkerInterceptors(new OpenTracingWorkerInterceptor())
                  .validateAndBuildWithDefaults())
          .setWorkflowTypes(WorkflowImpl.class)
          .setActivityImplementations(new ActivityImpl())
          .build();

  @Before
  public void setUp() {
    GlobalTracer.registerIfAbsent(mockTracer);
  }

  @After
  public void tearDown() {
    mockTracer.reset();
  }

  @ActivityInterface
  public interface TestActivity1 {
    @ActivityMethod
    String activity1(String input);
  }

  @ActivityInterface
  public interface TestActivity2 {
    @ActivityMethod
    String activity2(String input);
  }

  @WorkflowInterface
  public interface TestWorkflow {
    @WorkflowMethod
    String workflow1(String input);
  }

  public static class ActivityImpl implements TestActivity1, TestActivity2 {
    @Override
    public String activity1(String input) {
      return "bar";
    }

    @Override
    public String activity2(String input) {
      return "bar";
    }
  }

  private static final AtomicInteger failureCounter = new AtomicInteger(1);

  public static class WorkflowImpl implements TestWorkflow {
    private final TestActivity1 activity1 =
        Workflow.newActivityStub(
            TestActivity1.class,
            ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofMinutes(1))
                .validateAndBuildWithDefaults());

    private final TestActivity2 activity2 =
        Workflow.newActivityStub(
            TestActivity2.class,
            ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofMinutes(1))
                .validateAndBuildWithDefaults());

    @Override
    public String workflow1(String input) {
      activity1.activity1(input);

      if (failureCounter.getAndDecrement() > 0) {
        throw ApplicationFailure.newFailure("fail", "fail");
      }

      return activity2.activity2(input);
    }
  }

  /*
   * We are checking that spans structure looks like this:
   * ClientFunction
   *       |
   *     child
   *       v
   * StartWorkflow:TestWorkflow
   *       |
   *    follow
   *       |
   *       l__-> RunWorkflow:TestWorkflow, (failure in workflow, retry needed), RunWorkflow:TestWorkflow
   *                        |                                                            |
   *                      child                                                        child
   *                        v                                                            v
   *             StartActivity:Activity1 -follow> RunActivity:Activity1         StartActivity:Activity1 -follow> RunActivity:Activity1, StartActivity:Activity2 -follow> RunActivity:Activity2
   */
  @Test
  public void testWorkflowRetrySpanStructure() {
    MockSpan span = mockTracer.buildSpan("ClientFunction").start();

    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    try (Scope scope = mockTracer.scopeManager().activate(span)) {
      TestWorkflow workflow =
          client.newWorkflowStub(
              TestWorkflow.class,
              WorkflowOptions.newBuilder()
                  .setRetryOptions(
                      RetryOptions.newBuilder()
                          .setInitialInterval(Duration.ofSeconds(1))
                          .setBackoffCoefficient(1.0)
                          .setMaximumAttempts(2)
                          .build())
                  .setWorkflowExecutionTimeout(Duration.ofMinutes(1))
                  .setTaskQueue(testWorkflowRule.getTaskQueue())
                  .validateBuildWithDefaults());
      assertEquals("bar", workflow.workflow1("input"));
    } finally {
      span.finish();
    }

    OpenTracingSpansHelper spansHelper = new OpenTracingSpansHelper(mockTracer.finishedSpans());

    MockSpan clientSpan = spansHelper.getSpanByOperationName("ClientFunction");

    MockSpan workflowStartSpan = spansHelper.getByParentSpan(clientSpan).get(0);
    assertEquals(clientSpan.context().spanId(), workflowStartSpan.parentId());
    assertEquals("StartWorkflow:TestWorkflow", workflowStartSpan.operationName());

    List<MockSpan> workflowRunSpans = spansHelper.getByParentSpan(workflowStartSpan);
    assertEquals(2, workflowRunSpans.size());

    MockSpan workflowFirstRunSpan = workflowRunSpans.get(0);
    assertEquals(workflowStartSpan.context().spanId(), workflowFirstRunSpan.parentId());
    assertEquals("RunWorkflow:TestWorkflow", workflowFirstRunSpan.operationName());
    assertEquals(true, workflowFirstRunSpan.tags().get(StandardTagNames.FAILED));

    List<MockSpan> workflowFirstRunChildren = spansHelper.getByParentSpan(workflowFirstRunSpan);
    assertEquals(1, workflowFirstRunChildren.size());

    MockSpan activity1StartSpan = workflowFirstRunChildren.get(0);
    assertEquals(workflowFirstRunSpan.context().spanId(), activity1StartSpan.parentId());
    assertEquals("StartActivity:Activity1", activity1StartSpan.operationName());

    MockSpan activity1RunSpan = spansHelper.getByParentSpan(activity1StartSpan).get(0);
    assertEquals(activity1StartSpan.context().spanId(), activity1RunSpan.parentId());
    assertEquals("RunActivity:Activity1", activity1RunSpan.operationName());

    MockSpan workflowSecondRunSpan = workflowRunSpans.get(1);
    assertEquals(workflowStartSpan.context().spanId(), workflowSecondRunSpan.parentId());
    assertEquals("RunWorkflow:TestWorkflow", workflowSecondRunSpan.operationName());

    List<MockSpan> workflowSecondRunChildren = spansHelper.getByParentSpan(workflowSecondRunSpan);
    assertEquals(2, workflowSecondRunChildren.size());

    activity1StartSpan = workflowSecondRunChildren.get(0);
    assertEquals(workflowSecondRunSpan.context().spanId(), activity1StartSpan.parentId());
    assertEquals("StartActivity:Activity1", activity1StartSpan.operationName());

    activity1RunSpan = spansHelper.getByParentSpan(activity1StartSpan).get(0);
    assertEquals(activity1StartSpan.context().spanId(), activity1RunSpan.parentId());
    assertEquals("RunActivity:Activity1", activity1RunSpan.operationName());

    MockSpan activity2StartSpan = workflowSecondRunChildren.get(1);
    assertEquals(workflowSecondRunSpan.context().spanId(), activity2StartSpan.parentId());
    assertEquals("StartActivity:Activity2", activity2StartSpan.operationName());

    MockSpan activity2RunSpan = spansHelper.getByParentSpan(activity2StartSpan).get(0);
    assertEquals(activity2StartSpan.context().spanId(), activity2RunSpan.parentId());
    assertEquals("RunActivity:Activity2", activity2RunSpan.operationName());
  }
}
