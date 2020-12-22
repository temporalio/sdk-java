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

package io.temporal.workflow;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.worker.WorkerOptions;
import java.util.UUID;
import org.junit.rules.TestRule;
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

  private final Class<?>[] workflowTypes;
  private final Object[] activityImplementations;
  private final TestWorkflowEnvironment testEnvironment;
  private final WorkerOptions workerOptions;
  private final boolean useExternalService;
  private String taskQueue;

  private TestWorkflowRule(
      TestWorkflowEnvironment testEnvironment,
      boolean useExternalService,
      Class<?>[] workflowTypes,
      Object[] activityImplementations,
      WorkerOptions workerOptions) {
    this.testEnvironment = testEnvironment;
    this.useExternalService = useExternalService;
    this.workflowTypes = workflowTypes;
    this.activityImplementations = activityImplementations;
    this.workerOptions = workerOptions;
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static class Builder {
    private WorkerOptions workerOptions;
    private String namespace;
    private Class<?>[] workflowTypes;
    private Object[] activityImplementations;
    private boolean useExternalService;
    private String target;

    private Builder() {}

    public Builder setWorkerOptions(WorkerOptions options) {
      this.workerOptions = options;
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

    public TestWorkflowRule build() {
      namespace = namespace == null ? "UnitTest" : namespace;
      WorkflowClientOptions clientOptions =
          WorkflowClientOptions.newBuilder().setNamespace(namespace).build();
      WorkerFactoryOptions factoryOptions = WorkerFactoryOptions.newBuilder().build();
      TestEnvironmentOptions testOptions =
          TestEnvironmentOptions.newBuilder()
              .setWorkflowClientOptions(clientOptions)
              .setWorkerFactoryOptions(factoryOptions)
              .setUseExternalService(useExternalService)
              .setTarget(target)
              .build();
      TestWorkflowEnvironment testEnvironment = TestWorkflowEnvironment.newInstance(testOptions);
      workerOptions = workerOptions == null ? WorkerOptions.newBuilder().build() : workerOptions;
      return new TestWorkflowRule(
          testEnvironment,
          useExternalService,
          workflowTypes,
          activityImplementations,
          workerOptions);
    }
  }

  @Override
  public Statement apply(Statement base, Description description) {
    taskQueue = init(description);
    return new Statement() {
      @Override
      public void evaluate() throws Throwable {
        start();
        base.evaluate();
        shutdown();
      }
    };
  }

  private void shutdown() {
    testEnvironment.close();
  }

  private void start() {
    testEnvironment.start();
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
}
