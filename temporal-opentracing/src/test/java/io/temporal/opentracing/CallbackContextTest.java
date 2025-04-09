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

package io.temporal.opentracing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.mock.MockSpan;
import io.opentracing.mock.MockTracer;
import io.opentracing.util.ThreadLocalScopeManager;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.workflow.*;
import java.time.Duration;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;

public class CallbackContextTest {

  private static final MockTracer mockTracer =
      new MockTracer(new ThreadLocalScopeManager(), MockTracer.Propagator.TEXT_MAP);

  private final OpenTracingOptions OT_OPTIONS =
      OpenTracingOptions.newBuilder().setTracer(mockTracer).build();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowClientOptions(
              WorkflowClientOptions.newBuilder()
                  .setInterceptors(new OpenTracingClientInterceptor(OT_OPTIONS))
                  .validateAndBuildWithDefaults())
          .setWorkerFactoryOptions(
              WorkerFactoryOptions.newBuilder()
                  .setWorkerInterceptors(new OpenTracingWorkerInterceptor(OT_OPTIONS))
                  .validateAndBuildWithDefaults())
          .setWorkflowTypes(WorkflowImpl.class)
          .setActivityImplementations(new ActivityImpl())
          .build();

  @After
  public void tearDown() {
    mockTracer.reset();
  }

  @ActivityInterface
  public interface TestActivity {
    @ActivityMethod
    boolean activity();
  }

  @WorkflowInterface
  public interface TestWorkflow {
    @WorkflowMethod
    boolean workflow();
  }

  public static class ActivityImpl implements TestActivity {
    @Override
    public boolean activity() {
      Span span = mockTracer.buildSpan("someWork").start();
      try (Scope ignored = mockTracer.scopeManager().activate(span)) {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      } finally {
        span.finish();
      }
      return true;
    }
  }

  public static class WorkflowImpl implements TestWorkflow {
    private final TestActivity activity =
        Workflow.newActivityStub(
            TestActivity.class,
            ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofMinutes(1))
                .validateAndBuildWithDefaults());

    @Override
    public boolean workflow() {
      return Async.function(activity::activity)
          .thenCompose(
              result ->
                  Promise.allOf(
                      Async.function(activity::activity), Async.function(activity::activity)))
          .thenCompose(result -> Async.function(activity::activity))
          .get();
    }
  }

  @Test
  public void testCallbackContext() {
    MockSpan span = mockTracer.buildSpan("ClientFunction").start();

    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    try (Scope scope = mockTracer.scopeManager().activate(span)) {
      TestWorkflow workflow =
          client.newWorkflowStub(
              TestWorkflow.class,
              WorkflowOptions.newBuilder()
                  .setTaskQueue(testWorkflowRule.getTaskQueue())
                  .validateBuildWithDefaults());
      assertTrue(workflow.workflow());
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

    assertEquals(4, spansHelper.getByParentSpan(workflowRunSpan).stream().count());

    spansHelper
        .getByParentSpan(workflowRunSpan)
        .forEach(
            (activityStartSpan) -> {
              assertEquals(workflowRunSpan.context().spanId(), activityStartSpan.parentId());
              assertEquals("StartActivity:Activity", activityStartSpan.operationName());

              MockSpan activityRunSpan = spansHelper.getByParentSpan(activityStartSpan).get(0);
              assertEquals(activityStartSpan.context().spanId(), activityRunSpan.parentId());
              assertEquals("RunActivity:Activity", activityRunSpan.operationName());

              MockSpan activityWorkSpan = spansHelper.getByParentSpan(activityRunSpan).get(0);
              assertEquals(activityRunSpan.context().spanId(), activityWorkSpan.parentId());
              assertEquals("someWork", activityWorkSpan.operationName());
            });
  }
}
