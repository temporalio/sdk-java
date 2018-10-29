/*
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

package com.uber.cadence.worker;

import static org.junit.Assert.assertNotNull;

import com.uber.cadence.activity.ActivityMethod;
import com.uber.cadence.activity.ActivityOptions;
import com.uber.cadence.client.WorkflowClient;
import com.uber.cadence.client.WorkflowOptions;
import com.uber.cadence.client.WorkflowStub;
import com.uber.cadence.testing.TestEnvironmentOptions;
import com.uber.cadence.testing.TestWorkflowEnvironment;
import com.uber.cadence.workflow.Async;
import com.uber.cadence.workflow.Promise;
import com.uber.cadence.workflow.Workflow;
import com.uber.cadence.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RunWith(Parameterized.class)
public class WorkerStressTests {
  public static final String DOMAIN = "UnitTest";

  private static final boolean skipDockerService =
      Boolean.parseBoolean(System.getenv("SKIP_DOCKER_SERVICE"));

  @Parameterized.Parameter public boolean useExternalService;

  @Parameterized.Parameters(name = "{1}")
  public static Object[] data() {
    if (skipDockerService) {
      return new Object[][] {{false, "TestService"}};
    } else {
      return new Object[][] {{true, "Docker"}, {false, "TestService"}};
    }
  }

  @Parameterized.Parameter(1)
  public String testType;

  @Rule public TestName testName = new TestName();

  // Todo: Write a unit test specifically to test DecisionTaskWithHistoryIteratorImpl
  @Ignore("Takes a long time to run")
  @Test
  public void longHistoryWorkflowsCompleteSuccessfully() {

    // Arrange
    String taskListName = "veryLongWorkflow";

    TestEnvironmentWrapper wrapper =
        new TestEnvironmentWrapper(
            new Worker.FactoryOptions.Builder().setMaxWorkflowThreadCount(200).build());
    Worker.Factory factory = wrapper.getWorkerFactory();
    Worker worker = factory.newWorker(taskListName, new WorkerOptions.Builder().build());
    worker.registerWorkflowImplementationTypes(ActivitiesWorkflowImpl.class);
    worker.registerActivitiesImplementations(new ActivitiesImpl());
    factory.start();

    WorkflowOptions workflowOptions =
        new WorkflowOptions.Builder()
            .setTaskList(taskListName)
            .setExecutionStartToCloseTimeout(Duration.ofSeconds(250))
            .setTaskStartToCloseTimeout(Duration.ofSeconds(30))
            .build();
    WorkflowStub workflow =
        wrapper
            .getWorkflowClient()
            .newUntypedWorkflowStub("ActivitiesWorkflow::execute", workflowOptions);

    // Act
    // This will yeild around 10000 events which is above the page limit returned by the server.
    WorkflowParams w = new WorkflowParams();
    w.CadenceSleep = Duration.ofSeconds(0);
    w.ChainSequence = 50;
    w.ConcurrentCount = 50;
    w.PayloadSizeBytes = 10000;
    w.TaskListName = taskListName;

    workflow.start(w);
    assertNotNull("I'm done.", workflow.getResult(String.class));
    wrapper.close();
  }

  @Test
  public void selfEvictionDoesNotCauseDeadlock() throws InterruptedException {

    // Arrange
    String taskListName = "veryLongWorkflow" + UUID.randomUUID();

    TestEnvironmentWrapper wrapper =
        new TestEnvironmentWrapper(
            new Worker.FactoryOptions.Builder()
                .setDisableStickyExecution(false)
                .setMaxWorkflowThreadCount(2)
                .build());
    Worker.Factory factory = wrapper.getWorkerFactory();
    Worker worker = factory.newWorker(taskListName, new WorkerOptions.Builder().build());
    worker.registerWorkflowImplementationTypes(ActivitiesWorkflowImpl.class);
    worker.registerActivitiesImplementations(new ActivitiesImpl());
    factory.start();

    WorkflowOptions workflowOptions =
        new WorkflowOptions.Builder()
            .setTaskList(taskListName)
            .setExecutionStartToCloseTimeout(Duration.ofSeconds(250))
            .setTaskStartToCloseTimeout(Duration.ofSeconds(30))
            .build();
    WorkflowStub workflow =
        wrapper
            .getWorkflowClient()
            .newUntypedWorkflowStub("ActivitiesWorkflow::execute", workflowOptions);

    // Act
    WorkflowParams w = new WorkflowParams();
    w.CadenceSleep = Duration.ofSeconds(0);
    w.ChainSequence = 1;
    w.ConcurrentCount = 15;
    w.PayloadSizeBytes = 100;
    w.TaskListName = taskListName;

    // This will attempt to self evict given that there are only two threads available
    workflow.start(w);

    // Wait enough time to trigger self eviction
    Thread.sleep(Duration.ofSeconds(1).toMillis());

    // Start a second workflow and kick the previous one out
    WorkflowStub workflow2 =
        wrapper
            .getWorkflowClient()
            .newUntypedWorkflowStub("ActivitiesWorkflow::execute", workflowOptions);
    w.ConcurrentCount = 1;
    workflow2.start(w);
    assertNotNull("I'm done.", workflow2.getResult(String.class));
    wrapper.close();
  }

  // Todo: refactor TestEnvironment to toggle between real and test service.
  private class TestEnvironmentWrapper {

    private TestWorkflowEnvironment testEnv;
    private Worker.Factory factory;

    public TestEnvironmentWrapper(Worker.FactoryOptions options) {
      if (options == null) {
        options = new Worker.FactoryOptions.Builder().setDisableStickyExecution(false).build();
      }
      factory = new Worker.Factory(DOMAIN, options);
      TestEnvironmentOptions testOptions =
          new TestEnvironmentOptions.Builder().setDomain(DOMAIN).setFactoryOptions(options).build();
      testEnv = TestWorkflowEnvironment.newInstance(testOptions);
    }

    private Worker.Factory getWorkerFactory() {
      return useExternalService ? factory : testEnv.getWorkerFactory();
    }

    private WorkflowClient getWorkflowClient() {
      return useExternalService ? WorkflowClient.newInstance(DOMAIN) : testEnv.newWorkflowClient();
    }

    private void close() {
      factory.shutdown(Duration.ofSeconds(1));
      testEnv.close();
    }
  }

  public static class WorkflowParams {

    public int ChainSequence;
    public int ConcurrentCount;
    public String TaskListName;
    public int PayloadSizeBytes;
    public Duration CadenceSleep;
  }

  public interface ActivitiesWorkflow {

    @WorkflowMethod()
    String execute(WorkflowParams params);
  }

  public static class ActivitiesWorkflowImpl implements ActivitiesWorkflow {
    private static final Logger log = LoggerFactory.getLogger(ActivitiesWorkflowImpl.class);

    @Override
    public String execute(WorkflowParams params) {
      SleepActivity activity =
          Workflow.newActivityStub(
              SleepActivity.class,
              new ActivityOptions.Builder()
                  .setTaskList(params.TaskListName)
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

        Workflow.sleep(params.CadenceSleep);
      }
      return "I'm done";
    }
  }

  public interface SleepActivity {

    @ActivityMethod()
    void sleep(int chain, int concurrency, byte[] bytes);
  }

  public static class ActivitiesImpl implements SleepActivity {
    private static final Logger log = LoggerFactory.getLogger("sleep-activity");

    @Override
    public void sleep(int chain, int concurrency, byte[] bytes) {
      log.info("sleep called");
    }
  }
}
