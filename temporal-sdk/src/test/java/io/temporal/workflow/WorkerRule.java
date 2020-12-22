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
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.worker.WorkerOptions;
import java.util.UUID;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

public class WorkerRule implements TestRule {

  private static final boolean useExternalService =
      Boolean.parseBoolean(System.getenv("USE_DOCKER_SERVICE"));
  private static final String serviceAddress = System.getenv("TEMPORAL_SERVICE_ADDRESS");
  private WorkflowServiceStubs service;
  private Class<?>[] workflowTypes;
  private Object[] activityImplementations;
  private WorkerFactory workerFactory;
  private WorkflowClient workflowClient;
  private TestWorkflowEnvironment testEnvironment;
  private WorkerOptions workerOptions;
  private Worker worker;
  private String taskQueue;

  static class Builder {
    private WorkerOptions workerOptions;
    private String namespace;
    private Class<?>[] workflowTypes;
    private Object[] activityImplementations;

    public Builder withWorkerOptions(WorkerOptions options) {
      this.workerOptions = options;
      return this;
    }

    public Builder withNamespace(String namespace) {
      this.namespace = namespace;
      return this;
    }

    public Builder withWorkflowTypes(Class<?>... workflowTypes) {
      this.workflowTypes = workflowTypes;
      return this;
    }

    public Builder withActivityImplementations(Object... activityImplementations) {
      this.activityImplementations = activityImplementations;
      return this;
    }

    public WorkerRule build() {
      namespace = namespace == null ? "UnitTest" : namespace;
      WorkflowClientOptions clientOptions =
          WorkflowClientOptions.newBuilder().setNamespace(namespace).build();
      WorkerFactoryOptions factoryOptions = WorkerFactoryOptions.newBuilder().build();
      TestEnvironmentOptions testOptions =
          TestEnvironmentOptions.newBuilder()
              .setWorkflowClientOptions(clientOptions)
              .setWorkerFactoryOptions(factoryOptions)
              .build();
      TestWorkflowEnvironment testEnvironment = TestWorkflowEnvironment.newInstance(testOptions);
      workerOptions = workerOptions == null ? WorkerOptions.newBuilder().build() : workerOptions;
      final WorkflowServiceStubs service;
      final WorkflowClient workflowClient;
      final WorkerFactory workerFactory;
      if (useExternalService) {
        service =
            WorkflowServiceStubs.newInstance(
                WorkflowServiceStubsOptions.newBuilder().setTarget(serviceAddress).build());
        workflowClient = WorkflowClient.newInstance(service, clientOptions);
        workerFactory = WorkerFactory.newInstance(workflowClient, factoryOptions);
      } else {
        service = testEnvironment.getWorkflowService();
        workflowClient = testEnvironment.getWorkflowClient();
        workerFactory = testEnvironment.getWorkerFactory();
      }

      return new WorkerRule(
          service,
          workflowTypes,
          activityImplementations,
          workerFactory,
          workflowClient,
          testEnvironment,
          workerOptions);
    }
  }

  private WorkerRule(
      WorkflowServiceStubs service,
      Class<?>[] workflowTypes,
      Object[] activityImplementations,
      WorkerFactory workerFactory,
      WorkflowClient workflowClient,
      TestWorkflowEnvironment testEnvironment,
      WorkerOptions workerOptions) {
    this.service = service;
    this.workflowTypes = workflowTypes;
    this.activityImplementations = activityImplementations;
    this.workerFactory = workerFactory;
    this.workflowClient = workflowClient;
    this.testEnvironment = testEnvironment;
    this.workerOptions = workerOptions;
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
    if (useExternalService) {
      workerFactory.shutdown();
    } else {
      testEnvironment.close();
    }
  }

  private void start() {
    if (useExternalService) {
      workerFactory.start();
    } else {
      testEnvironment.start();
    }
  }

  private String init(Description description) {
    String testMethod = description.getMethodName();
    String taskQueue = "WorkflowTest-" + testMethod + "-" + UUID.randomUUID().toString();
    if (useExternalService) {
      worker = workerFactory.newWorker(taskQueue, workerOptions);
    } else {
      worker = testEnvironment.newWorker(taskQueue, workerOptions);
    }
    worker.registerWorkflowImplementationTypes(workflowTypes);
    worker.registerActivitiesImplementations(activityImplementations);
    return taskQueue;
  }

  public Worker getWorker() {
    return worker;
  }

  public String getTaskQueue() {
    return taskQueue;
  }

  public WorkflowClient getWorkflowClient() {
    return workflowClient;
  }
}
