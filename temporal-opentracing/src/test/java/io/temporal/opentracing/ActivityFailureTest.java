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

public class ActivityFailureTest {

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
          .setActivityImplementations(new FailingActivityImpl())
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
  public interface TestActivity {
    @ActivityMethod
    String activity(String input);
  }

  @WorkflowInterface
  public interface TestWorkflow {
    @WorkflowMethod
    String workflow(String input);
  }

  private static final AtomicInteger failureCounter = new AtomicInteger(1);

  public static class FailingActivityImpl implements TestActivity {
    @Override
    public String activity(String input) {
      int counter = failureCounter.getAndDecrement();
      if (counter > 0) {
        throw ApplicationFailure.newFailure("fail", "fail");
      } else {
        return "bar";
      }
    }
  }

  public static class WorkflowImpl implements TestWorkflow {
    private final TestActivity activity =
        Workflow.newActivityStub(
            TestActivity.class,
            ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofMinutes(1))
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2).build())
                .validateAndBuildWithDefaults());

    @Override
    public String workflow(String input) {
      return activity.activity(input);
    }
  }

  /*
   * We are checking that spans structure looks like this:
   * ClientFunction
   *       |
   *     child
   *       v
   * StartWorkflow:TestWorkflow  -follow>  RunWorkflow:TestWorkflow
   *                                                  |
   *                                                child
   *                                                  v
   *                                       StartActivity:Activity -follow> RunActivity:Activity(failed), RunActivity:Activity
   */
  @Test
  public void testActivityFailureSpanStructure() {
    MockSpan span = mockTracer.buildSpan("ClientFunction").start();

    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    try (Scope scope = mockTracer.scopeManager().activate(span)) {
      TestWorkflow workflow =
          client.newWorkflowStub(
              TestWorkflow.class,
              WorkflowOptions.newBuilder()
                  .setTaskQueue(testWorkflowRule.getTaskQueue())
                  .validateBuildWithDefaults());
      assertEquals("bar", workflow.workflow("input"));
    } finally {
      span.finish();
    }

    OpenTracingSpansHelper spansHelper = new OpenTracingSpansHelper(mockTracer.finishedSpans());

    MockSpan clientSpan = spansHelper.getSpanByOperationName("ClientFunction");

    MockSpan workflowStartSpan = spansHelper.getByParentSpan(clientSpan).get(0);
    assertEquals(clientSpan.context().spanId(), workflowStartSpan.parentId());
    assertEquals("StartWorkflow:TestWorkflow", workflowStartSpan.operationName());

    MockSpan workflowRunSpan = spansHelper.getByParentSpan(workflowStartSpan).get(0);
    assertEquals(workflowStartSpan.context().spanId(), workflowRunSpan.parentId());
    assertEquals("RunWorkflow:TestWorkflow", workflowRunSpan.operationName());

    MockSpan activityStartSpan = spansHelper.getByParentSpan(workflowRunSpan).get(0);
    assertEquals(workflowRunSpan.context().spanId(), activityStartSpan.parentId());
    assertEquals("StartActivity:Activity", activityStartSpan.operationName());

    List<MockSpan> activityRunSpans = spansHelper.getByParentSpan(activityStartSpan);

    MockSpan activityFailRunSpan = activityRunSpans.get(0);
    assertEquals(activityStartSpan.context().spanId(), activityFailRunSpan.parentId());
    assertEquals("RunActivity:Activity", activityFailRunSpan.operationName());
    assertEquals(true, activityFailRunSpan.tags().get(StandardTagNames.FAILED));

    MockSpan activitySuccessfulRunSpan = activityRunSpans.get(1);
    assertEquals(activityStartSpan.context().spanId(), activitySuccessfulRunSpan.parentId());
    assertEquals("RunActivity:Activity", activitySuccessfulRunSpan.operationName());
  }
}
