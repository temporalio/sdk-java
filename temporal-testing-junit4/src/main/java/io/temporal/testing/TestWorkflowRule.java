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

import static io.temporal.client.WorkflowClient.QUERY_TYPE_STACK_TRACE;

import io.temporal.client.*;
import io.temporal.common.interceptors.WorkerInterceptor;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.worker.WorkerOptions;
import java.time.Duration;
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

  public static final String NAMESPACE = "UnitTest";
  public static final String UUID_REGEXP =
      "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
  // When set to true increases test, activity and workflow timeouts to large values to support
  // stepping through code in a debugger without timing out.
  public static final boolean DEBUGGER_TIMEOUTS = false;
  // Enable to regenerate JsonFiles used for replay testing.
  // Only enable when USE_DOCKER_SERVICE is true
  public static final boolean USE_EXTERNAL_SERVICE =
      Boolean.parseBoolean(System.getenv("USE_DOCKER_SERVICE"));

  private final TestWatcher watchman =
      new TestWatcher() {
        @Override
        protected void failed(Throwable e, Description description) {
          System.err.println("WORKFLOW EXECUTION HISTORIES:\n" + testEnvironment.getDiagnostics());
        }
      };

  public Timeout globalTimeout;
  private String taskQueue;
  private final boolean doNotStart;
  private final boolean useExternalService;
  private final Class<?>[] workflowTypes;
  private final Object[] activityImplementations;
  private final WorkerInterceptor[] interceptors;
  private final WorkerOptions workerOptions;
  private final TestWorkflowEnvironment testEnvironment;

  private TestWorkflowRule(
      TestWorkflowEnvironment testEnvironment,
      boolean useExternalService,
      Class<?>[] workflowTypes,
      Object[] activityImplementations,
      WorkerOptions workerOptions,
      long testTimeoutSeconds,
      boolean doNotStart,
      WorkerInterceptor[] interceptors) {
    this.testEnvironment = testEnvironment;
    this.useExternalService = useExternalService;
    this.workflowTypes = workflowTypes;
    this.activityImplementations = activityImplementations;
    this.workerOptions = workerOptions;
    this.globalTimeout = Timeout.seconds(testTimeoutSeconds);
    this.doNotStart = doNotStart;
    this.interceptors = interceptors;
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static class Builder {
    private static final long DEFAULT_TEST_TIMEOUT_SECONDS = 10;

    private WorkerOptions workerOptions;
    private WorkflowClientOptions workflowClientOptions;
    private String namespace;
    private Class<?>[] workflowTypes;
    private Object[] activityImplementations;
    private boolean useExternalService;
    private String target;
    private long testTimeoutSeconds;
    private boolean doNotStart;
    private WorkerInterceptor[] workerInterceptors;

    private Builder() {}

    public Builder setWorkerOptions(WorkerOptions options) {
      this.workerOptions = options;
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

    public Builder setWorkerInterceptors(WorkerInterceptor... workerInterceptors) {
      this.workerInterceptors = workerInterceptors;
      return this;
    }

    public Builder setWorkflowTypes(Class<?>... workflowTypes) {
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
      namespace = namespace == null ? "UnitTest" : namespace;

      if (workflowClientOptions == null) {
        namespace = namespace == null ? "UnitTest" : namespace;
        workflowClientOptions = WorkflowClientOptions.newBuilder().setNamespace(namespace).build();
      }
      WorkerFactoryOptions factoryOptions =
          WorkerFactoryOptions.newBuilder().setWorkerInterceptors(workerInterceptors).build();
      TestEnvironmentOptions testOptions =
          TestEnvironmentOptions.newBuilder()
              .setWorkflowClientOptions(workflowClientOptions)
              .setWorkerFactoryOptions(factoryOptions)
              .setUseExternalService(useExternalService)
              .setTarget(target)
              .build();
      TestWorkflowEnvironment testEnvironment = TestWorkflowEnvironment.newInstance(testOptions);
      workerOptions = workerOptions == null ? WorkerOptions.newBuilder().build() : workerOptions;
      return new TestWorkflowRule(
          testEnvironment,
          useExternalService,
          workflowTypes == null ? new Class[0] : workflowTypes,
          activityImplementations == null ? new Object[0] : activityImplementations,
          workerOptions,
          testTimeoutSeconds == 0 ? DEFAULT_TEST_TIMEOUT_SECONDS : testTimeoutSeconds,
          doNotStart,
          workerInterceptors);
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

  private void shutdown() {
    testEnvironment.close();
    if (interceptors != null) {
      TracingWorkerInterceptor tracer = getInterceptor(TracingWorkerInterceptor.class);
      if (tracer != null) {
        tracer.assertExpected();
      }
    }
  }

  private void start() {
    if (!doNotStart) {
      testEnvironment.start();
    }
  }

  private String init(Description description) {
    String testMethod = description.getMethodName();
    String taskQueue = "WorkflowTest-" + testMethod + "-" + UUID.randomUUID().toString();
    Worker worker = testEnvironment.newWorker(taskQueue, workerOptions);
    worker.registerWorkflowImplementationTypes(workflowTypes);
    worker.registerActivitiesImplementations(activityImplementations);
    return taskQueue;
  }

  /** Returns name of the task queue that test worker is polling. */
  public String getTaskQueue() {
    return taskQueue;
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

  /** Returns client to the Temporal service used to start and query workflows. */
  public WorkflowClient getWorkflowClient() {
    if (useExternalService) {
      return WorkflowClient.newInstance(
          testEnvironment.getWorkflowClient().getWorkflowServiceStubs(),
          WorkflowClientOptions.newBuilder().setNamespace(NAMESPACE).build());
    } else {
      return testEnvironment.getWorkflowClient();
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

  public static WorkflowOptions.Builder newWorkflowOptionsBuilder(String taskQueue) {
    if (DEBUGGER_TIMEOUTS) {
      return WorkflowOptions.newBuilder()
          .setWorkflowRunTimeout(Duration.ofSeconds(1000))
          .setWorkflowTaskTimeout(Duration.ofSeconds(60))
          .setTaskQueue(taskQueue);
    } else {
      return WorkflowOptions.newBuilder()
          .setWorkflowRunTimeout(Duration.ofHours(30))
          .setWorkflowTaskTimeout(Duration.ofSeconds(5))
          .setTaskQueue(taskQueue);
    }
  }
}
