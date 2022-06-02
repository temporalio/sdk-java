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

import static org.junit.Assert.*;

import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.mock.MockSpan;
import io.opentracing.mock.MockTracer;
import io.opentracing.util.ThreadLocalScopeManager;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.workflow.*;
import java.util.List;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;

public class AsyncChildWorkflowTest {

  private static final String BAGGAGE_ITEM_KEY = "baggage-item-key";

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
          .setWorkflowTypes(ParentWorkflowImpl.class, ChildWorkflowImpl.class)
          .build();

  @After
  public void tearDown() {
    mockTracer.reset();
  }

  @WorkflowInterface
  public interface ParentWorkflow {
    @WorkflowMethod
    String workflow(String input);
  }

  @WorkflowInterface
  public interface ChildWorkflow {
    @WorkflowMethod
    String childWorkflow(String input);
  }

  public static class ParentWorkflowImpl implements ParentWorkflow {
    @Override
    public String workflow(String input) {
      Span activeSpan = mockTracer.scopeManager().activeSpan();

      MockSpan mockSpan = (MockSpan) activeSpan;
      assertNotNull(activeSpan);
      assertNotEquals(0, mockSpan.parentId());

      ChildWorkflow child = Workflow.newChildWorkflowStub(ChildWorkflow.class);
      return Async.function(child::childWorkflow, input).get();
    }
  }

  public static class ChildWorkflowImpl implements ChildWorkflow {
    @Override
    public String childWorkflow(String input) {
      Span activeSpan = mockTracer.scopeManager().activeSpan();

      MockSpan mockSpan = (MockSpan) activeSpan;
      assertNotNull(activeSpan);
      assertNotEquals(0, mockSpan.parentId());

      return activeSpan.getBaggageItem(BAGGAGE_ITEM_KEY);
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
   *                                       StartChildWorkflow:ChildWorkflow -follow> RunWorkflow:ChildWorkflow
   */
  @Test
  public void asyncChildWFCorrectSpanStructureAndBaggagePropagation() {
    Span span = mockTracer.buildSpan("ClientFunction").start();

    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    try (Scope scope = mockTracer.scopeManager().activate(span)) {
      Span activeSpan = mockTracer.scopeManager().activeSpan();
      final String BAGGAGE_ITEM_VALUE = "baggage-item-value";
      activeSpan.setBaggageItem(BAGGAGE_ITEM_KEY, BAGGAGE_ITEM_VALUE);

      ParentWorkflow workflow =
          client.newWorkflowStub(
              ParentWorkflow.class,
              WorkflowOptions.newBuilder()
                  .setTaskQueue(testWorkflowRule.getTaskQueue())
                  .validateBuildWithDefaults());
      assertEquals(
          "Baggage item should be propagated all the way down to the child workflow",
          BAGGAGE_ITEM_VALUE,
          workflow.workflow("input"));
    } finally {
      span.finish();
    }

    OpenTracingSpansHelper spansHelper = new OpenTracingSpansHelper(mockTracer.finishedSpans());

    MockSpan clientSpan = spansHelper.getSpanByOperationName("ClientFunction");

    MockSpan workflowStartSpan = spansHelper.getByParentSpan(clientSpan).get(0);
    assertEquals(clientSpan.context().spanId(), workflowStartSpan.parentId());
    assertEquals("StartWorkflow:ParentWorkflow", workflowStartSpan.operationName());

    MockSpan workflowRunSpan = spansHelper.getByParentSpan(workflowStartSpan).get(0);
    assertEquals(workflowStartSpan.context().spanId(), workflowRunSpan.parentId());
    assertEquals("RunWorkflow:ParentWorkflow", workflowRunSpan.operationName());

    MockSpan childWorkflowStartSpan = spansHelper.getByParentSpan(workflowRunSpan).get(0);
    assertEquals(workflowRunSpan.context().spanId(), childWorkflowStartSpan.parentId());
    assertEquals("StartChildWorkflow:ChildWorkflow", childWorkflowStartSpan.operationName());

    List<MockSpan> childWorkflowRunSpans = spansHelper.getByParentSpan(childWorkflowStartSpan);

    MockSpan childWorkflowRunSpan = childWorkflowRunSpans.get(0);
    assertEquals(childWorkflowStartSpan.context().spanId(), childWorkflowRunSpan.parentId());
    assertEquals("RunWorkflow:ChildWorkflow", childWorkflowRunSpan.operationName());
  }
}
