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

package io.temporal.worker;

import static io.temporal.workflow.WorkflowTest.NAMESPACE;
import static org.junit.Assert.assertNotNull;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.workflow.Async;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RunWith(Parameterized.class)
public class WorkerStressTests {

  private static final boolean useDockerService =
      Boolean.parseBoolean(System.getenv("USE_DOCKER_SERVICE"));
  private static final String serviceAddress = System.getenv("TEMPORAL_SERVICE_ADDRESS");

  @Parameterized.Parameter public boolean useExternalService;

  @Parameterized.Parameters(name = "{1}")
  public static Object[] data() {
    if (!useDockerService) {
      return new Object[][] {{false, "TestService"}};
    } else {
      return new Object[][] {{true, "Docker"}};
    }
  }

  @Parameterized.Parameter(1)
  public String testType;

  @Rule public TestName testName = new TestName();

  @Test
  public void longHistoryWorkflowsCompleteSuccessfully() throws InterruptedException {

    // Arrange
    String taskQueueName = "veryLongWorkflow";

    TestEnvironmentWrapper wrapper =
        new TestEnvironmentWrapper(
            WorkerFactoryOptions.newBuilder().setMaxWorkflowThreadCount(200).build());
    WorkerFactory factory = wrapper.getWorkerFactory();
    Worker worker = factory.newWorker(taskQueueName, WorkerOptions.newBuilder().build());
    worker.registerWorkflowImplementationTypes(ActivitiesWorkflowImpl.class);
    worker.registerActivitiesImplementations(new ActivitiesImpl());
    factory.start();

    WorkflowOptions workflowOptions =
        WorkflowOptions.newBuilder()
            .setTaskQueue(taskQueueName)
            .setWorkflowRunTimeout(Duration.ofSeconds(250))
            .setWorkflowTaskTimeout(Duration.ofSeconds(30))
            .build();
    WorkflowStub workflow =
        wrapper.getWorkflowClient().newUntypedWorkflowStub("ActivitiesWorkflow", workflowOptions);

    // Act
    // This will yeild around 10000 events which is above the page limit returned by the server.
    WorkflowParams w = new WorkflowParams();
    w.TemporalSleepSeconds = 0;
    w.ChainSequence = 50;
    w.ConcurrentCount = 50;
    w.PayloadSizeBytes = 10000;
    w.TaskQueueName = taskQueueName;

    workflow.start(w);
    assertNotNull("I'm done.", workflow.getResult(String.class));
    wrapper.close();
  }

  @Test
  public void selfEvictionDoesNotCauseDeadlock() throws InterruptedException {

    // Arrange
    String taskQueueName = "veryLongWorkflow" + UUID.randomUUID();

    TestEnvironmentWrapper wrapper =
        new TestEnvironmentWrapper(
            WorkerFactoryOptions.newBuilder().setMaxWorkflowThreadCount(2).build());

    WorkerFactory factory = wrapper.getWorkerFactory();
    Worker worker = factory.newWorker(taskQueueName, WorkerOptions.newBuilder().build());
    worker.registerWorkflowImplementationTypes(ActivitiesWorkflowImpl.class);
    worker.registerActivitiesImplementations(new ActivitiesImpl());
    factory.start();

    WorkflowOptions workflowOptions =
        WorkflowOptions.newBuilder()
            .setTaskQueue(taskQueueName)
            .setWorkflowRunTimeout(Duration.ofSeconds(250))
            .setWorkflowTaskTimeout(Duration.ofSeconds(30))
            .build();
    WorkflowStub workflow =
        wrapper.getWorkflowClient().newUntypedWorkflowStub("ActivitiesWorkflow", workflowOptions);

    // Act
    WorkflowParams w = new WorkflowParams();
    w.TemporalSleepSeconds = 0;
    w.ChainSequence = 1;
    w.ConcurrentCount = 15;
    w.PayloadSizeBytes = 100;
    w.TaskQueueName = taskQueueName;

    // This will attempt to self evict given that there are only two threads available
    workflow.start(w);

    // Wait enough time to trigger self eviction
    Thread.sleep(Duration.ofSeconds(1).toMillis());

    // Start a second workflow and kick the previous one out
    WorkflowStub workflow2 =
        wrapper.getWorkflowClient().newUntypedWorkflowStub("ActivitiesWorkflow", workflowOptions);
    w.ConcurrentCount = 1;
    workflow2.start(w);
    assertNotNull("I'm done.", workflow2.getResult(String.class));
    wrapper.close();
  }

  // Todo: refactor TestEnvironment to toggle between real and test service.
  private class TestEnvironmentWrapper {

    private TestWorkflowEnvironment testEnv;
    WorkflowServiceStubs service;
    private WorkerFactory factory;

    public TestEnvironmentWrapper(WorkerFactoryOptions options) {
      options = WorkerFactoryOptions.newBuilder(options).validateAndBuildWithDefaults();
      WorkflowClientOptions clientOptions =
          WorkflowClientOptions.newBuilder().setNamespace(NAMESPACE).build();
      if (useDockerService) {
        service =
            WorkflowServiceStubs.newInstance(
                WorkflowServiceStubsOptions.newBuilder().setTarget(serviceAddress).build());
        WorkflowClient client = WorkflowClient.newInstance(service, clientOptions);
        factory = WorkerFactory.newInstance(client, options);
      } else {
        TestEnvironmentOptions testOptions =
            TestEnvironmentOptions.newBuilder()
                .setWorkflowClientOptions(clientOptions)
                .setWorkerFactoryOptions(options)
                .build();
        testEnv = TestWorkflowEnvironment.newInstance(testOptions);
      }
    }

    private WorkerFactory getWorkerFactory() {
      return useExternalService ? factory : testEnv.getWorkerFactory();
    }

    private WorkflowClient getWorkflowClient() {
      return useExternalService ? factory.getWorkflowClient() : testEnv.getWorkflowClient();
    }

    private void close() throws InterruptedException {
      if (factory != null) {
        factory.shutdown();
        factory.awaitTermination(10, TimeUnit.SECONDS);
        service.shutdownNow();
        service.awaitTermination(10, TimeUnit.SECONDS);
      } else {
        testEnv.close();
      }
    }
  }

  public static class WorkflowParams {

    public int ChainSequence;
    public int ConcurrentCount;
    public String TaskQueueName;
    public int PayloadSizeBytes;
    public int TemporalSleepSeconds;
  }

  @WorkflowInterface
  public interface ActivitiesWorkflow {

    @WorkflowMethod()
    String execute(WorkflowParams params);
  }

  public static class ActivitiesWorkflowImpl implements ActivitiesWorkflow {

    @Override
    public String execute(WorkflowParams params) {
      SleepActivity activity =
          Workflow.newActivityStub(
              SleepActivity.class,
              ActivityOptions.newBuilder()
                  .setTaskQueue(params.TaskQueueName)
                  .setScheduleToStartTimeout(Duration.ofMinutes(1))
                  .setStartToCloseTimeout(Duration.ofMinutes(1))
                  .setHeartbeatTimeout(Duration.ofSeconds(20))
                  .build());

      for (int i = 0; i < params.ChainSequence; i++) {
        List<Promise<Void>> promises = new ArrayList<>();
        for (int j = 0; j < params.ConcurrentCount; j++) {
          byte[] bytes = new byte[params.PayloadSizeBytes];
          new Random().nextBytes(bytes);
          Promise<Void> promise = Async.procedure(activity::sleep, i, j, bytes);
          promises.add(promise);
        }

        for (Promise<Void> promise : promises) {
          promise.get();
        }

        Workflow.sleep(Duration.ofSeconds(params.TemporalSleepSeconds));
      }
      return "I'm done";
    }
  }

  @ActivityInterface
  public interface SleepActivity {
    void sleep(int chain, int concurrency, byte[] bytes);
  }

  public static class ActivitiesImpl implements SleepActivity {
    private static final Logger log = LoggerFactory.getLogger("sleep-activity");

    @Override
    public void sleep(int chain, int concurrency, byte[] bytes) {
      log.trace("sleep called");
    }
  }
}
