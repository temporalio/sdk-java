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

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.history.v1.History;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.interceptors.WorkerInterceptor;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.worker.WorkerOptions;
import io.temporal.worker.WorkflowImplementationOptions;
import java.time.Instant;
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

  private final String namespace;
  private final boolean useExternalService;
  private final boolean doNotStart;
  private final Timeout globalTimeout;

  private final Class<?>[] workflowTypes;
  private final Object[] activityImplementations;
  private final WorkflowImplementationOptions workflowImplementationOptions;
  private final WorkerFactoryOptions workerFactoryOptions;
  private final WorkerOptions workerOptions;

  private String taskQueue;
  private final TestWorkflowEnvironment testEnvironment;
  private final TestWatcher watchman =
      new TestWatcher() {
        @Override
        protected void failed(Throwable e, Description description) {
          System.err.println("WORKFLOW EXECUTION HISTORIES:\n" + testEnvironment.getDiagnostics());
        }
      };

  private TestWorkflowRule(Builder builder) {

    doNotStart = builder.doNotStart;
    useExternalService = builder.useExternalService;
    namespace = (builder.namespace == null) ? "UnitTest" : builder.namespace;
    workflowTypes = (builder.workflowTypes == null) ? new Class[0] : builder.workflowTypes;
    activityImplementations =
        (builder.activityImplementations == null) ? new Object[0] : builder.activityImplementations;
    workerOptions =
        (builder.workerOptions == null)
            ? WorkerOptions.getDefaultInstance()
            : builder.workerOptions;
    workerFactoryOptions =
        (builder.workerFactoryOptions == null)
            ? WorkerFactoryOptions.getDefaultInstance()
            : builder.workerFactoryOptions;
    workflowImplementationOptions =
        (builder.workflowImplementationOptions == null)
            ? WorkflowImplementationOptions.getDefaultInstance()
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
            .setInitialTimeMillis(builder.initialTimeMillis)
            .build();

    testEnvironment = TestWorkflowEnvironment.newInstance(testOptions);
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static class Builder {

    private String namespace;
    private String target;
    private boolean useExternalService;
    private boolean doNotStart;
    private long testTimeoutSeconds;
    private long initialTimeMillis;

    private Class<?>[] workflowTypes;
    private Object[] activityImplementations;
    private WorkflowImplementationOptions workflowImplementationOptions;
    private WorkflowClientOptions workflowClientOptions;
    private WorkerFactoryOptions workerFactoryOptions;
    private WorkerOptions workerOptions;

    protected Builder() {}

    public Builder setWorkerOptions(WorkerOptions options) {
      this.workerOptions = options;
      return this;
    }

    public Builder setWorkerFactoryOptions(WorkerFactoryOptions options) {
      this.workerFactoryOptions = options;
      return this;
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
     * Set the initial time for the workflow virtual clock, milliseconds since epoch.
     *
     * <p>Default is current time
     */
    public Builder setInitialTimeMillis(long initialTimeMillis) {
      this.initialTimeMillis = initialTimeMillis;
      return this;
    }

    /**
     * Set the initial time for the workflow virtual clock.
     *
     * <p>Default is current time
     */
    public Builder setInitialTime(Instant initialTime) {
      this.initialTimeMillis = initialTime.toEpochMilli();
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
    if (getInterceptor(TracingWorkerInterceptor.class) != null) {
      TracingWorkerInterceptor tracer = getInterceptor(TracingWorkerInterceptor.class);
      if (tracer != null) {
        tracer.assertExpected();
      }
    }
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

  /** @return name of the task queue that test worker is polling. */
  public String getTaskQueue() {
    return taskQueue;
  }

  /** @return client to the Temporal service used to start and query workflows. */
  public WorkflowClient getWorkflowClient() {
    return testEnvironment.getWorkflowClient();
  }

  /** @return blockingStub */
  public WorkflowServiceGrpc.WorkflowServiceBlockingStub blockingStub() {
    return testEnvironment.getWorkflowService().blockingStub();
  }

  /** @return tracer. */
  public <T extends WorkerInterceptor> T getInterceptor(Class<T> type) {
    if (workerFactoryOptions.getWorkerInterceptors() != null) {
      for (WorkerInterceptor interceptor : workerFactoryOptions.getWorkerInterceptors()) {
        if (type.isInstance(interceptor)) {
          return (T) interceptor;
        }
      }
    }
    return null;
  }

  /** @return name of the task queue that test worker is polling. */
  public History getWorkflowExecutionHistory(WorkflowExecution execution) {
    GetWorkflowExecutionHistoryRequest request =
        GetWorkflowExecutionHistoryRequest.newBuilder()
            .setNamespace(namespace)
            .setExecution(execution)
            .build();
    return this.blockingStub().getWorkflowExecutionHistory(request).getHistory();
  }

  /**
   * This worker listens to the default task queue which is obtainable via the {@link
   * #getTaskQueue()} method.
   *
   * @return the default worker created for each test method.
   */
  public Worker getWorker() {
    return testEnvironment.getWorkerFactory().getWorker(getTaskQueue());
  }
}
