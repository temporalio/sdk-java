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

import static org.junit.Assert.*;
import static org.junit.Assert.assertEquals;

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
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;

public class ContinueAsNewTest {

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
          .setWorkflowTypes(WorkflowImpl.class)
          .build();

  @After
  public void tearDown() {
    mockTracer.reset();
  }

  @WorkflowInterface
  public interface TestWorkflow {
    @WorkflowMethod
    String workflow(String input, boolean continueAsNew);
  }

  public static class WorkflowImpl implements TestWorkflow {
    @Override
    public String workflow(String input, boolean continueAsNew) {
      Span activeSpan = mockTracer.scopeManager().activeSpan();

      MockSpan mockSpan = (MockSpan) activeSpan;
      assertNotNull(activeSpan);
      assertNotEquals(0, mockSpan.parentId());

      if (continueAsNew) {
        Workflow.continueAsNew(input);
      }
      return activeSpan.getBaggageItem(BAGGAGE_ITEM_KEY);
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
   *       ↳ follow>  RunWorkflow:TestWorkflow
   *                             |
   *                             ↳ -follow> StartContinueAsNewWorkflow:TestWorkflow  -follow> RunWorkflow:TestWorkflow
   */
  @Test
  public void continueAsNewCorrectSpanStructureAndBaggagePropagation() {
    Span span = mockTracer.buildSpan("ClientFunction").start();

    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    try (Scope scope = mockTracer.scopeManager().activate(span)) {
      Span activeSpan = mockTracer.scopeManager().activeSpan();
      final String BAGGAGE_ITEM_VALUE = "baggage-item-value";
      activeSpan.setBaggageItem(BAGGAGE_ITEM_KEY, BAGGAGE_ITEM_VALUE);

      TestWorkflow workflow =
          client.newWorkflowStub(
              TestWorkflow.class,
              WorkflowOptions.newBuilder()
                  .setTaskQueue(testWorkflowRule.getTaskQueue())
                  .validateBuildWithDefaults());
      assertEquals(
          "Baggage item should be propagated all the way down continue-as-new chain",
          BAGGAGE_ITEM_VALUE,
          workflow.workflow("input", true));
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

    MockSpan continueAsNewStartSpan = spansHelper.getByParentSpan(workflowRunSpan).get(0);
    assertEquals(workflowRunSpan.context().spanId(), continueAsNewStartSpan.parentId());
    assertEquals("StartContinueAsNewWorkflow:TestWorkflow", continueAsNewStartSpan.operationName());

    MockSpan continueAsNewRunSpan = spansHelper.getByParentSpan(continueAsNewStartSpan).get(0);
    assertEquals(continueAsNewStartSpan.context().spanId(), continueAsNewRunSpan.parentId());
    assertEquals("RunWorkflow:TestWorkflow", continueAsNewRunSpan.operationName());
  }
}
