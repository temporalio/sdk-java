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

package io.temporal.workflow.nexus;

import static io.temporal.testing.internal.SDKTestWorkflowRule.NAMESPACE;

import com.google.common.collect.ImmutableMap;
import com.uber.m3.tally.RootScopeBuilder;
import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.common.reporter.TestStatsReporter;
import io.temporal.nexus.Nexus;
import io.temporal.nexus.WorkflowClientOperationHandlers;
import io.temporal.serviceclient.MetricsTag;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.testing.internal.TracingWorkerInterceptor;
import io.temporal.worker.MetricsType;
import io.temporal.worker.WorkerMetricsTag;
import io.temporal.workflow.*;
import io.temporal.workflow.shared.TestNexusServices;
import java.time.Duration;
import java.util.Map;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class SyncClientOperationTest extends BaseNexusTest {
  private final TestStatsReporter reporter = new TestStatsReporter();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestNexus.class)
          .setMetricsScope(
              new RootScopeBuilder()
                  .reporter(reporter)
                  .reportEvery(com.uber.m3.util.Duration.ofMillis(10)))
          .setNexusServiceImplementation(new TestNexusServiceImpl())
          .build();

  @Override
  protected SDKTestWorkflowRule getTestWorkflowRule() {
    return testWorkflowRule;
  }

  @Test
  public void syncClientOperation() {
    TestUpdatedWorkflow workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestUpdatedWorkflow.class);
    Assert.assertTrue(workflowStub.execute().startsWith("Update ID:"));
    testWorkflowRule
        .getInterceptor(TracingWorkerInterceptor.class)
        .setExpected(
            "interceptExecuteWorkflow " + SDKTestWorkflowRule.UUID_REGEXP,
            "registerUpdateHandler update",
            "newThread workflow-method",
            "executeNexusOperation TestNexusService1.operation",
            "startNexusOperation TestNexusService1.operation");
    // Test metrics all tasks should have
    Map<String, String> nexusWorkerTags =
        ImmutableMap.<String, String>builder()
            .putAll(MetricsTag.defaultTags(NAMESPACE))
            .put(MetricsTag.WORKER_TYPE, WorkerMetricsTag.WorkerType.NEXUS_WORKER.getValue())
            .put(MetricsTag.TASK_QUEUE, testWorkflowRule.getTaskQueue())
            .buildKeepingLast();
    reporter.assertTimer(MetricsType.NEXUS_SCHEDULE_TO_START_LATENCY, nexusWorkerTags);
    Map<String, String> operationTags =
        ImmutableMap.<String, String>builder()
            .putAll(nexusWorkerTags)
            .put(MetricsTag.NEXUS_SERVICE, "TestNexusService1")
            .put(MetricsTag.NEXUS_OPERATION, "operation")
            .buildKeepingLast();
    reporter.assertTimer(MetricsType.NEXUS_EXEC_LATENCY, operationTags);
    reporter.assertTimer(MetricsType.NEXUS_TASK_E2E_LATENCY, operationTags);
    // Test our custom metric
    reporter.assertCounter("operation", operationTags, 1);
  }

  @WorkflowInterface
  public interface TestUpdatedWorkflow {

    @WorkflowMethod
    String execute();

    @UpdateMethod
    String update(String arg);
  }

  public static class TestNexus implements TestUpdatedWorkflow {
    @Override
    public String execute() {
      NexusOperationOptions options =
          NexusOperationOptions.newBuilder()
              .setScheduleToCloseTimeout(Duration.ofSeconds(1))
              .build();
      NexusServiceOptions serviceOptions =
          NexusServiceOptions.newBuilder()
              .setEndpoint(getEndpointName())
              .setOperationOptions(options)
              .build();
      // Try to call a synchronous operation in a blocking way
      TestNexusServices.TestNexusService1 serviceStub =
          Workflow.newNexusServiceStub(TestNexusServices.TestNexusService1.class, serviceOptions);
      return serviceStub.operation(Workflow.getInfo().getWorkflowId());
    }

    @Override
    public String update(String arg) {
      return "Update ID: " + Workflow.getCurrentUpdateInfo().get().getUpdateId();
    }
  }

  @ServiceImpl(service = TestNexusServices.TestNexusService1.class)
  public class TestNexusServiceImpl {
    @OperationImpl
    public OperationHandler<String, String> operation() {
      // Implemented inline
      return WorkflowClientOperationHandlers.sync(
          (ctx, details, client, id) -> {
            Nexus.getOperationContext().getMetricsScope().counter("operation").inc(1);
            return client
                .newWorkflowStub(TestUpdatedWorkflow.class, id)
                .update("Update from operation");
          });
    }
  }
}
