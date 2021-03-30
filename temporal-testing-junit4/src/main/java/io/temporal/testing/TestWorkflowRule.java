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

package io.temporal.testing;

import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.interceptors.WorkerInterceptor;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.worker.WorkerOptions;
import io.temporal.worker.WorkflowImplementationOptions;
import java.util.UUID;
import org.junit.rules.TestRule;
import org.junit.rules.TestWatcher;
import org.junit.rules.Timeout;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

/**
 * Test rule that sets up test environment, simplifying workflow worker creation and shutdown. Can
 * be used with both in-memory and standalone temporal service. (see {@link
 * Builder#setUseExternalService(boolean)} and {@link Builder#setTarget(String)}})
 *
 * <p>Example of usage:
 *
 * <pre><code>
 *   public class MyTest {
 *
 *  {@literal @}Rule
 *   public TestWorkflowRule workflowRule =
 *       TestWorkflowRule.newBuilder()
 *           .setWorkflowTypes(TestWorkflowImpl.class)
 *           .setActivityImplementations(new TestActivities())
 *           .build();
 *
 *  {@literal @}Test
 *   public void testMyWorkflow() {
 *       TestWorkflow workflow = workflowRule.getWorkflowClient().newWorkflowStub(
 *                 TestWorkflow.class, WorkflowOptions.newBuilder().setTaskQueue(workflowRule.getTaskQueue()).build());
 *       ...
 *   }
 * </code></pre>
 */
public class TestWorkflowRule implements TestRule {

  private static final long DEFAULT_TEST_TIMEOUT_SECONDS = 10;

  private final boolean doNotStart;
  private final boolean useExternalService;
  private final Class<?>[] workflowTypes;
  private final Object[] activityImplementations;
  private final WorkerInterceptor[] interceptors;
  private final WorkflowImplementationOptions workflowImplementationOptions;
  private final WorkerOptions workerOptions;
  private final WorkerFactoryOptions workerFactoryOptions;
  private final TestWorkflowEnvironment testEnvironment;
  private final TestWatcher watchman =
      new TestWatcher() {
        @Override
        protected void failed(Throwable e, Description description) {
          System.err.println("WORKFLOW EXECUTION HISTORIES:\n" + testEnvironment.getDiagnostics());
        }
      };
  private final Timeout globalTimeout;
  private String taskQueue;

  private TestWorkflowRule(Builder builder) {

    String namespace = (builder.namespace == null) ? "UnitTest" : builder.namespace;

    doNotStart = builder.doNotStart;
    interceptors = builder.workerInterceptors;
    useExternalService = builder.useExternalService;
    workflowTypes = (builder.workflowTypes == null) ? new Class[0] : builder.workflowTypes;
    activityImplementations =
        (builder.activityImplementations == null) ? new Object[0] : builder.activityImplementations;
    workerOptions =
        (builder.workerOptions == null)
            ? WorkerOptions.newBuilder().build()
            : builder.workerOptions;
    workerFactoryOptions =
        (builder.workerFactoryOptions == null)
            ? WorkerFactoryOptions.newBuilder().setWorkerInterceptors(interceptors).build()
            : builder.workerFactoryOptions.toBuilder().setWorkerInterceptors(interceptors).build();
    workflowImplementationOptions =
        (builder.workflowImplementationOptions == null)
            ? WorkflowImplementationOptions.newBuilder().build()
            : builder.workflowImplementationOptions;
    globalTimeout =
        Timeout.seconds(
            builder.testTimeoutSeconds == 0
                ? DEFAULT_TEST_TIMEOUT_SECONDS
                : builder.testTimeoutSeconds);

    WorkflowClientOptions clientOptions =
        (builder.workflowClientOptions == null)
            ? WorkflowClientOptions.newBuilder().setNamespace(namespace).build()
            : builder.workflowClientOptions.toBuilder().setNamespace(namespace).build();
    TestEnvironmentOptions testOptions =
        TestEnvironmentOptions.newBuilder()
            .setWorkflowClientOptions(clientOptions)
            .setWorkerFactoryOptions(workerFactoryOptions)
            .setUseExternalService(useExternalService)
            .setTarget(builder.target)
            .build();

    testEnvironment = TestWorkflowEnvironment.newInstance(testOptions);
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static class Builder {

    private WorkflowImplementationOptions workflowImplementationOptions;
    private WorkerOptions workerOptions;
    private WorkerFactoryOptions workerFactoryOptions;
    private WorkflowClientOptions workflowClientOptions;
    private String namespace;
    private Class<?>[] workflowTypes;
    private Object[] activityImplementations;
    private boolean useExternalService;
    private String target;
    private long testTimeoutSeconds;
    private boolean doNotStart;
    private WorkerInterceptor[] workerInterceptors;

    protected Builder() {}

    public Builder setWorkerOptions(WorkerOptions options) {
      this.workerOptions = options;
      return this;
    }

    public void setWorkerFactoryOptions(WorkerFactoryOptions options) {
      this.workerFactoryOptions = options;
    }

    /**
     * Override {@link WorkflowClientOptions} for test environment. If set, takes precedence over
     * {@link #setNamespace(String) namespace}.
     */
    public Builder setWorkflowClientOptions(WorkflowClientOptions workflowClientOptions) {
      this.workflowClientOptions = workflowClientOptions;
      return this;
    }

    public Builder setNamespace(String namespace) {
      this.namespace = namespace;
      return this;
    }

    public Builder setWorkerInterceptors(WorkerInterceptor... workerInterceptors) {
      this.workerInterceptors = workerInterceptors;
      return this;
    }

    public Builder setWorkflowTypes(Class<?>... workflowTypes) {
      this.workflowTypes = workflowTypes;
      return this;
    }

    public Builder setWorkflowTypes(
        WorkflowImplementationOptions implementationOptions, Class<?>... workflowTypes) {
      this.workflowImplementationOptions = implementationOptions;
      this.workflowTypes = workflowTypes;
      return this;
    }

    public Builder setActivityImplementations(Object... activityImplementations) {
      this.activityImplementations = activityImplementations;
      return this;
    }

    /**
     * Switches between in-memory and external temporal service implementations.
     *
     * @param useExternalService use external service if true.
     *     <p>Default is false.
     */
    public Builder setUseExternalService(boolean useExternalService) {
      this.useExternalService = useExternalService;
      return this;
    }

    /**
     * Optional parameter that defines an endpoint which will be used for the communication with
     * standalone temporal service. Has no effect if {@link #setUseExternalService(boolean)} is set
     * to false.
     *
     * <p>Default is to use 127.0.0.1:7233
     */
    public Builder setTarget(String target) {
      this.target = target;
      return this;
    }

    /** Global test timeout. Default is 10 seconds. */
    public Builder setTestTimeoutSeconds(long testTimeoutSeconds) {
      this.testTimeoutSeconds = testTimeoutSeconds;
      return this;
    }

    /**
     * When set to true the {@link TestWorkflowEnvironment#start()} is not called by the rule before
     * executing the test. This to support tests that register activities and workflows with workers
     * directly instead of using only {@link TestWorkflowRule.Builder}.
     */
    public Builder setDoNotStart(boolean doNotStart) {
      this.doNotStart = doNotStart;
      return this;
    }

    public TestWorkflowRule build() {
      return new TestWorkflowRule(this);
    }
  }

  @Override
  public Statement apply(Statement base, Description description) {
    taskQueue = init(description);
    Statement testWorkflowStatement =
        new Statement() {
          @Override
          public void evaluate() throws Throwable {
            start();
            base.evaluate();
            shutdown();
          }
        };
    return watchman.apply(globalTimeout.apply(testWorkflowStatement, description), description);
  }

  private String init(Description description) {
    String testMethod = description.getMethodName();
    String taskQueue = "WorkflowTest-" + testMethod + "-" + UUID.randomUUID().toString();
    Worker worker = testEnvironment.newWorker(taskQueue, workerOptions);
    worker.registerWorkflowImplementationTypes(workflowImplementationOptions, workflowTypes);
    worker.registerActivitiesImplementations(activityImplementations);
    return taskQueue;
  }

  private void start() {
    if (!doNotStart) {
      testEnvironment.start();
    }
  }

  protected void shutdown() throws Throwable {
    testEnvironment.close();
    if (interceptors != null) {
      TracingWorkerInterceptor tracer = getInterceptor(TracingWorkerInterceptor.class);
      if (tracer != null) {
        tracer.assertExpected();
      }
    }
  }

  /** Returns blockingStub */
  public WorkflowServiceGrpc.WorkflowServiceBlockingStub blockingStub() {
    return testEnvironment.getWorkflowService().blockingStub();
  }

  /** Returns tracer. */
  public <T extends WorkerInterceptor> T getInterceptor(Class<T> type) {
    if (interceptors != null) {
      for (WorkerInterceptor interceptor : interceptors) {
        if (type.isInstance(interceptor)) {
          return (T) interceptor;
        }
      }
    }
    return null;
  }

  /** Returns name of the task queue that test worker is polling. */
  public String getTaskQueue() {
    return taskQueue;
  }

  /**
   * Returns name of the task queue that test worker is polling.
   *
   * @return
   */
  public GetWorkflowExecutionHistoryResponse getWorkflowExecutionHistory(
      GetWorkflowExecutionHistoryRequest request) {
    return this.blockingStub().getWorkflowExecutionHistory(request);
  }

  /** Returns client to the Temporal service used to start and query workflows. */
  public WorkflowClient getWorkflowClient() {
    return testEnvironment.getWorkflowClient();
  }

  /**
   * See {@link Builder#setUseExternalService(boolean)}
   *
   * @return true if the rule is using external temporal service.
   */
  public boolean isUseExternalService() {
    return useExternalService;
  }

  public TestWorkflowEnvironment getTestEnvironment() {
    return testEnvironment;
  }
}
