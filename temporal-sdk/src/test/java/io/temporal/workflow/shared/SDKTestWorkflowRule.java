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

package io.temporal.workflow.shared;

import static io.temporal.client.WorkflowClient.QUERY_TYPE_STACK_TRACE;

import com.google.common.base.Charsets;
import com.google.common.base.Throwables;
import com.google.common.io.CharSink;
import com.google.common.io.Files;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowQueryException;
import io.temporal.client.WorkflowStub;
import io.temporal.common.interceptors.WorkerInterceptor;
import io.temporal.internal.common.WorkflowExecutionHistory;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.TestWorkflowRule;
import io.temporal.worker.WorkerOptions;
import io.temporal.worker.WorkflowImplementationOptions;
import io.temporal.workflow.Functions;
import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SDKTestWorkflowRule implements TestRule {

  public static final String NAMESPACE = "UnitTest";
  public static final String BINARY_CHECKSUM = "testChecksum";
  public static final String ANNOTATION_TASK_QUEUE = "WorkflowTest-testExecute[Docker]";
  public static final String UUID_REGEXP =
      "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
  // Enable to regenerate JsonFiles used for replay testing.
  public static final boolean REGENERATE_JSON_FILES = false;
  // Only enable when USE_DOCKER_SERVICE is true
  public static final Boolean useExternalService =
      Boolean.parseBoolean(System.getenv("USE_DOCKER_SERVICE"));
  public static final String temporalServiceAddress = System.getenv("TEMPORAL_SERVICE_ADDRESS");
  private static final List<ScheduledFuture<?>> delayedCallbacks = new ArrayList<>();
  private static final ScheduledExecutorService scheduledExecutor =
      new ScheduledThreadPoolExecutor(1);
  private static final Logger log = LoggerFactory.getLogger(SDKTestWorkflowRule.class);
  private final TestWorkflowRule testWorkflowRule;

  private SDKTestWorkflowRule(TestWorkflowRule.Builder testWorkflowRuleBuilder) {
    if (useExternalService) {
      testWorkflowRuleBuilder.setUseExternalService(true);
    }
    testWorkflowRule = testWorkflowRuleBuilder.build();
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static class Builder {
    TestWorkflowRule.Builder testWorkflowRuleBuilder;

    public Builder() {
      testWorkflowRuleBuilder = TestWorkflowRule.newBuilder();
    }

    public Builder setWorkerOptions(WorkerOptions options) {
      testWorkflowRuleBuilder.setWorkerOptions(options);
      return this;
    }

    public Builder setWorkflowClientOptions(WorkflowClientOptions workflowClientOptions) {
      testWorkflowRuleBuilder.setWorkflowClientOptions(workflowClientOptions);
      return this;
    }

    public Builder setNamespace(String namespace) {
      testWorkflowRuleBuilder.setNamespace(namespace);
      return this;
    }

    public Builder setWorkerInterceptors(WorkerInterceptor... workerInterceptors) {
      testWorkflowRuleBuilder.setWorkerInterceptors(workerInterceptors);
      return this;
    }

    public Builder setWorkflowTypes(Class<?>... workflowTypes) {
      testWorkflowRuleBuilder.setWorkflowTypes(workflowTypes);
      return this;
    }

    public Builder setWorkflowTypes(
        WorkflowImplementationOptions implementationOptions, Class<?>... workflowTypes) {
      testWorkflowRuleBuilder.setWorkflowTypes(implementationOptions, workflowTypes);
      return this;
    }

    public Builder setActivityImplementations(Object... activityImplementations) {
      testWorkflowRuleBuilder.setActivityImplementations(activityImplementations);
      return this;
    }

    public Builder setUseExternalService(boolean useExternalService) {
      testWorkflowRuleBuilder.setUseExternalService(useExternalService);
      return this;
    }

    public Builder setTarget(String target) {
      testWorkflowRuleBuilder.setTarget(target);
      return this;
    }

    public Builder setTestTimeoutSeconds(long testTimeoutSeconds) {
      testWorkflowRuleBuilder.setTestTimeoutSeconds(testTimeoutSeconds);
      return this;
    }

    public Builder setDoNotStart(boolean doNotStart) {
      testWorkflowRuleBuilder.setDoNotStart(doNotStart);
      return this;
    }

    public SDKTestWorkflowRule build() {
      return new SDKTestWorkflowRule(testWorkflowRuleBuilder);
    }
  }

  public Statement apply(Statement base, Description description) {
    return testWorkflowRule.apply(base, description);
  }

  public <T extends WorkerInterceptor> T getInterceptor(Class<T> type) {
    return testWorkflowRule.getInterceptor(type);
  }

  public String getTaskQueue() {
    return testWorkflowRule.getTaskQueue();
  }

  public WorkflowClient getWorkflowClient() {
    return testWorkflowRule.getWorkflowClient();
  }

  public boolean isUseExternalService() {
    return testWorkflowRule.isUseExternalService();
  }

  public TestWorkflowEnvironment getTestEnvironment() {
    return testWorkflowRule.getTestEnvironment();
  }

  public <T> T newWorkflowStub(Class<T> workflow) {
    return getWorkflowClient()
        .newWorkflowStub(workflow, TestOptions.newWorkflowOptionsForTaskQueue(getTaskQueue()));
  }

  public <T> T newWorkflowStubTimeoutOptions(Class<T> workflow) {
    return getWorkflowClient()
        .newWorkflowStub(workflow, TestOptions.newWorkflowOptionsWithTimeouts(getTaskQueue()));
  }

  public <T> T newWorkflowStub200sTimeoutOptions(Class<T> workflow) {
    return getWorkflowClient()
        .newWorkflowStub(
            workflow, TestOptions.newWorkflowOptionsForTaskQueue200sTimeout(getTaskQueue()));
  }

  public <T> WorkflowStub newUntypedWorkflowStub(String workflow) {
    return getWorkflowClient()
        .newUntypedWorkflowStub(
            workflow, TestOptions.newWorkflowOptionsForTaskQueue(getTaskQueue()));
  }

  public <T> WorkflowStub newUntypedWorkflowStubTimeoutOptions(String workflow) {
    return getWorkflowClient()
        .newUntypedWorkflowStub(
            workflow, TestOptions.newWorkflowOptionsWithTimeouts(getTaskQueue()));
  }

  /** Used to ensure that workflow first workflow task is executed. */
  public static void waitForOKQuery(WorkflowStub stub) {
    while (true) {
      try {
        String stackTrace = stub.query(QUERY_TYPE_STACK_TRACE, String.class);
        if (!stackTrace.isEmpty()) {
          break;
        }
      } catch (WorkflowQueryException e) {
      }
    }
  }

  public <R> void addWorkflowImplementationFactory(
      Class<R> factoryImpl, Functions.Func<R> factoryFunc) {
    this.getTestEnvironment()
        .getWorkerFactory()
        .getWorker(this.getTaskQueue())
        .addWorkflowImplementationFactory(factoryImpl, factoryFunc);
  }

  public static void regenerateHistoryForReplay(
      WorkflowServiceStubs service, WorkflowExecution execution, String fileName) {
    if (REGENERATE_JSON_FILES) {
      GetWorkflowExecutionHistoryRequest request =
          GetWorkflowExecutionHistoryRequest.newBuilder()
              .setNamespace(NAMESPACE)
              .setExecution(execution)
              .build();
      GetWorkflowExecutionHistoryResponse response =
          service.blockingStub().getWorkflowExecutionHistory(request);
      WorkflowExecutionHistory history = new WorkflowExecutionHistory(response.getHistory());
      String json = history.toPrettyPrintedJson();
      String projectPath = System.getProperty("user.dir");
      String resourceFile = projectPath + "/src/test/resources/" + fileName + ".json";
      File file = new File(resourceFile);
      CharSink sink = Files.asCharSink(file, Charsets.UTF_8);
      try {
        sink.write(json);
      } catch (IOException e) {
        Throwables.propagateIfPossible(e, RuntimeException.class);
      }
      log.info("Regenerated history file: " + resourceFile);
    }
  }

  // TODO: Refactor testEnvironment to support testing through real service to avoid this
  // switches
  public void registerDelayedCallback(Duration delay, Runnable r) {
    if (useExternalService) {
      ScheduledFuture<?> result =
          scheduledExecutor.schedule(r, delay.toMillis(), TimeUnit.MILLISECONDS);
      delayedCallbacks.add(result);
    } else {
      testWorkflowRule.getTestEnvironment().registerDelayedCallback(delay, r);
    }
  }

  private void setTestWorkflowRuleShutdown() {
    getTestEnvironment().shutdown();
  }

  protected void shutdown() throws Throwable {
    setTestWorkflowRuleShutdown();
    for (ScheduledFuture<?> result : delayedCallbacks) {
      if (result.isDone() && !result.isCancelled()) {
        try {
          result.get();
        } catch (InterruptedException e) {
        } catch (ExecutionException e) {
          throw e.getCause();
        }
      }
    }
  }

  public void sleep(Duration d) {
    if (useExternalService) {
      try {
        Thread.sleep(d.toMillis());
      } catch (InterruptedException e) {
        throw new RuntimeException("Interrupted", e);
      }
    } else {
      testWorkflowRule.getTestEnvironment().sleep(d);
    }
  }
}
