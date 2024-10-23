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
import io.nexusrpc.OperationUnsuccessfulException;
import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationHandlerException;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowFailedException;
import io.temporal.common.reporter.TestStatsReporter;
import io.temporal.failure.ApplicationFailure;
import io.temporal.serviceclient.MetricsTag;
import io.temporal.testUtils.Eventually;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.MetricsType;
import io.temporal.worker.WorkerMetricsTag;
import io.temporal.workflow.*;
import io.temporal.workflow.shared.TestNexusServices;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import java.util.Map;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class OperationFailMetricTest {
  private final TestStatsReporter reporter = new TestStatsReporter();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestNexus.class)
          .setNexusServiceImplementation(new TestNexusServiceImpl())
          .setMetricsScope(
              new RootScopeBuilder()
                  .reporter(reporter)
                  .reportEvery(com.uber.m3.util.Duration.ofMillis(10)))
          .build();

  private ImmutableMap.Builder<String, String> getBaseTags() {
    return ImmutableMap.<String, String>builder()
        .putAll(MetricsTag.defaultTags(NAMESPACE))
        .put(MetricsTag.WORKER_TYPE, WorkerMetricsTag.WorkerType.NEXUS_WORKER.getValue())
        .put(MetricsTag.TASK_QUEUE, testWorkflowRule.getTaskQueue())
        .put(MetricsTag.NEXUS_SERVICE, "TestNexusService1")
        .put(MetricsTag.NEXUS_OPERATION, "operation");
  }

  @Test
  public void failOperationMetrics() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);

    Assert.assertThrows(WorkflowFailedException.class, () -> workflowStub.execute("fail"));

    Map<String, String> execFailedTags =
        getBaseTags().put(MetricsTag.TASK_FAILURE_TYPE, "operation_failed").buildKeepingLast();
    Eventually.assertEventually(
        Duration.ofSeconds(3),
        () -> {
          reporter.assertCounter(MetricsType.NEXUS_EXEC_FAILED_COUNTER, execFailedTags, 1);
        });
  }

  @Test
  public void failHandlerBadRequestMetrics() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    Assert.assertThrows(WorkflowFailedException.class, () -> workflowStub.execute("handlererror"));

    Map<String, String> execFailedTags =
        getBaseTags()
            .put(MetricsTag.TASK_FAILURE_TYPE, "handler_error_BAD_REQUEST")
            .buildKeepingLast();
    Eventually.assertEventually(
        Duration.ofSeconds(3),
        () -> {
          reporter.assertCounter(MetricsType.NEXUS_EXEC_FAILED_COUNTER, execFailedTags, 1);
        });
  }

  @Test
  public void failHandlerAlreadyStartedMetrics() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    Assert.assertThrows(
        WorkflowFailedException.class, () -> workflowStub.execute("already-started"));

    Map<String, String> execFailedTags =
        getBaseTags()
            .put(MetricsTag.TASK_FAILURE_TYPE, "handler_error_BAD_REQUEST")
            .buildKeepingLast();
    Eventually.assertEventually(
        Duration.ofSeconds(3),
        () -> {
          reporter.assertCounter(MetricsType.NEXUS_EXEC_FAILED_COUNTER, execFailedTags, 1);
        });
  }

  @Test
  public void failHandlerRetryableApplicationFailureMetrics() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    Assert.assertThrows(
        WorkflowFailedException.class, () -> workflowStub.execute("retryable-application-failure"));

    Map<String, String> execFailedTags =
        getBaseTags()
            .put(MetricsTag.TASK_FAILURE_TYPE, "handler_error_INTERNAL")
            .buildKeepingLast();
    Eventually.assertEventually(
        Duration.ofSeconds(3),
        () -> {
          reporter.assertCounter(
              MetricsType.NEXUS_EXEC_FAILED_COUNTER, execFailedTags, c -> c >= 1);
        });
  }

  @Test
  public void failHandlerNonRetryableApplicationFailureMetrics() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    Assert.assertThrows(
        WorkflowFailedException.class,
        () -> workflowStub.execute("non-retryable-application-failure"));

    Map<String, String> execFailedTags =
        getBaseTags()
            .put(MetricsTag.TASK_FAILURE_TYPE, "handler_error_BAD_REQUEST")
            .buildKeepingLast();
    Eventually.assertEventually(
        Duration.ofSeconds(3),
        () -> {
          reporter.assertCounter(MetricsType.NEXUS_EXEC_FAILED_COUNTER, execFailedTags, 1);
        });
  }

  @Test(timeout = 20000)
  public void failHandlerSleepMetrics() throws InterruptedException {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    Assert.assertThrows(WorkflowFailedException.class, () -> workflowStub.execute("sleep"));

    Map<String, String> execFailedTags =
        getBaseTags().put(MetricsTag.TASK_FAILURE_TYPE, "internal_sdk_error").buildKeepingLast();
    Eventually.assertEventually(
        Duration.ofSeconds(3),
        () -> {
          reporter.assertCounter(MetricsType.NEXUS_EXEC_FAILED_COUNTER, execFailedTags, 1);
        });
  }

  @Test
  public void failHandlerErrorMetrics() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    Assert.assertThrows(WorkflowFailedException.class, () -> workflowStub.execute("error"));
    Map<String, String> execFailedTags =
        getBaseTags()
            .put(MetricsTag.TASK_FAILURE_TYPE, "handler_error_INTERNAL")
            .buildKeepingLast();
    Eventually.assertEventually(
        Duration.ofSeconds(3),
        () -> {
          reporter.assertCounter(
              MetricsType.NEXUS_EXEC_FAILED_COUNTER, execFailedTags, c -> c >= 1);
        });
  }

  public static class TestNexus implements TestWorkflow1 {
    @Override
    public String execute(String operation) {
      TestNexusServices.TestNexusService1 testNexusService =
          Workflow.newNexusServiceStub(
              TestNexusServices.TestNexusService1.class,
              NexusServiceOptions.newBuilder()
                  .setOperationOptions(
                      NexusOperationOptions.newBuilder()
                          .setScheduleToCloseTimeout(Duration.ofSeconds(10))
                          .build())
                  .build());
      return testNexusService.operation(operation);
    }
  }

  @ServiceImpl(service = TestNexusServices.TestNexusService1.class)
  public class TestNexusServiceImpl {
    @OperationImpl
    public OperationHandler<String, String> operation() {
      // Implemented inline
      return OperationHandler.sync(
          (ctx, details, operation) -> {
            switch (operation) {
              case "success":
                return operation;
              case "fail":
                throw new OperationUnsuccessfulException("fail");
              case "handlererror":
                throw new OperationHandlerException(
                    OperationHandlerException.ErrorType.BAD_REQUEST, "handlererror");
              case "already-started":
                throw new WorkflowExecutionAlreadyStarted(
                    WorkflowExecution.getDefaultInstance(), "TestWorkflowType", null);
              case "retryable-application-failure":
                throw ApplicationFailure.newFailure("fail", "TestFailure");
              case "non-retryable-application-failure":
                throw ApplicationFailure.newNonRetryableFailure("fail", "TestFailure");
              case "sleep":
                try {
                  Thread.sleep(11000);
                } catch (InterruptedException e) {
                  throw new RuntimeException(e);
                }
                return operation;
              case "error":
                throw new Error("error");
              default:
                // Should never happen
                Assert.fail();
            }
          });
    }
  }
}
