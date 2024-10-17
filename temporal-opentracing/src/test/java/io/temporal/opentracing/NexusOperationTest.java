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

import io.nexusrpc.Operation;
import io.nexusrpc.Service;
import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.opentracing.Scope;
import io.opentracing.mock.MockSpan;
import io.opentracing.mock.MockTracer;
import io.opentracing.util.ThreadLocalScopeManager;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.nexus.WorkflowClientOperationHandlers;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.workflow.*;
import java.time.Duration;
import java.util.List;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;

public class NexusOperationTest {

  private final MockTracer mockTracer =
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
          .setWorkflowTypes(WorkflowImpl.class, OtherWorkflowImpl.class)
          .setNexusServiceImplementation(new TestNexusServiceImpl())
          .build();

  @After
  public void tearDown() {
    mockTracer.reset();
  }

  @Service
  public interface TestNexusService {
    @Operation
    String operation(String input);
  }

  @ServiceImpl(service = TestNexusService.class)
  public class TestNexusServiceImpl {
    @OperationImpl
    public OperationHandler<String, String> operation() {
      return WorkflowClientOperationHandlers.fromWorkflowMethod(
          (context, details, client, input) ->
              client.newWorkflowStub(
                      TestOtherWorkflow.class,
                      WorkflowOptions.newBuilder().setWorkflowId(details.getRequestId()).build())
                  ::workflow);
    }
  }

  @WorkflowInterface
  public interface TestWorkflow {
    @WorkflowMethod
    String workflow(String input);
  }

  @WorkflowInterface
  public interface TestOtherWorkflow {
    @WorkflowMethod
    String workflow(String input);
  }

  public static class OtherWorkflowImpl implements TestOtherWorkflow {
    @Override
    public String workflow(String input) {
      return "Hello, " + input + "!";
    }
  }

  public static class WorkflowImpl implements TestWorkflow {
    private final TestNexusService nexusService =
        Workflow.newNexusServiceStub(
            TestNexusService.class,
            NexusServiceOptions.newBuilder()
                .setOperationOptions(
                    NexusOperationOptions.newBuilder()
                        .setScheduleToCloseTimeout(Duration.ofSeconds(10))
                        .build())
                .build());

    @Override
    public String workflow(String input) {
      return nexusService.operation(input);
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
   *                                       ExecuteNexusOperation:TestNexusService.operation -follow> StartNexusOperation:TestNexusService.operation
   *                                                                                                                             |
   *                                                                                                                           child
   *                                                                                                                             v
   *                                                                                                                StartWorkflow:TestOtherWorkflow  -follow>  RunWorkflow:TestOtherWorkflow
   */
  @Test
  public void testNexusOperation() {
    MockSpan span = mockTracer.buildSpan("ClientFunction").start();

    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    try (Scope scope = mockTracer.scopeManager().activate(span)) {
      TestWorkflow workflow =
          client.newWorkflowStub(
              TestWorkflow.class,
              WorkflowOptions.newBuilder()
                  .setTaskQueue(testWorkflowRule.getTaskQueue())
                  .validateBuildWithDefaults());
      assertEquals("Hello, input!", workflow.workflow("input"));
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

    MockSpan executeNexusOperationSpan = spansHelper.getByParentSpan(workflowRunSpan).get(0);
    assertEquals(workflowRunSpan.context().spanId(), executeNexusOperationSpan.parentId());
    assertEquals(
        "ExecuteNexusOperation:TestNexusService.operation",
        executeNexusOperationSpan.operationName());

    List<MockSpan> startNexusOperationSpans =
        spansHelper.getByParentSpan(executeNexusOperationSpan);

    MockSpan startNexusOperationSpan = startNexusOperationSpans.get(0);
    assertEquals(executeNexusOperationSpan.context().spanId(), startNexusOperationSpan.parentId());
    assertEquals(
        "StartNexusOperation:TestNexusService.operation", startNexusOperationSpan.operationName());

    MockSpan startOtherWorkflowSpan = spansHelper.getByParentSpan(startNexusOperationSpan).get(0);
    assertEquals(startNexusOperationSpan.context().spanId(), startOtherWorkflowSpan.parentId());
    assertEquals("StartWorkflow:TestOtherWorkflow", startOtherWorkflowSpan.operationName());

    MockSpan otherWorkflowRunSpan = spansHelper.getByParentSpan(startOtherWorkflowSpan).get(0);
    assertEquals(startOtherWorkflowSpan.context().spanId(), otherWorkflowRunSpan.parentId());
    assertEquals("RunWorkflow:TestOtherWorkflow", otherWorkflowRunSpan.operationName());
  }
}
