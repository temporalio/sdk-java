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

package io.temporal.worker;

import static io.temporal.testing.internal.SDKTestWorkflowRule.NAMESPACE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.uber.m3.tally.RootScopeBuilder;
import com.uber.m3.tally.Scope;
import com.uber.m3.util.ImmutableMap;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.reporter.TestStatsReporter;
import io.temporal.internal.worker.WorkflowExecutorCache;
import io.temporal.serviceclient.MetricsTag;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Async;
import io.temporal.workflow.CompletablePromise;
import io.temporal.workflow.Promise;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow2;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RunWith(Parameterized.class)
public class StickyWorkerTest {

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

  private Scope metricsScope;
  private TestStatsReporter reporter;

  // TODO(maxim): refactor all of this ugliness into a service based implementation of
  // TestWorkflowEnvironment
  @Before
  public void setUp() {
    reporter = new TestStatsReporter();
    metricsScope =
        new RootScopeBuilder()
            .reporter(reporter)
            .reportEvery(com.uber.m3.util.Duration.ofSeconds(10));
  }

  @Test
  public void whenStickyIsEnabledThenTheWorkflowIsCachedSignals() throws Exception {
    // Arrange
    String taskQueueName = "cachedStickyTest_Signal";

    TestEnvironmentWrapper wrapper =
        new TestEnvironmentWrapper(WorkerFactoryOptions.newBuilder().build());
    WorkerFactory factory = wrapper.getWorkerFactory();
    Worker worker = factory.newWorker(taskQueueName, WorkerOptions.newBuilder().build());
    worker.registerWorkflowImplementationTypes(GreetingSignalWorkflowImpl.class);
    factory.start();

    WorkflowOptions workflowOptions =
        WorkflowOptions.newBuilder()
            .setTaskQueue(taskQueueName)
            .setWorkflowRunTimeout(Duration.ofDays(30))
            .setWorkflowTaskTimeout(Duration.ofSeconds(30))
            .setWorkflowIdReusePolicy(
                WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
            .build();
    GreetingSignalWorkflow workflow =
        wrapper.getWorkflowClient().newWorkflowStub(GreetingSignalWorkflow.class, workflowOptions);

    WorkflowClient.start(workflow::getGreeting);

    // Wait for workflow to start. We don't use OK query here to don't mess with the cache hit
    // counters
    Thread.sleep(500);

    workflow.waitForName("World");
    String greeting = workflow.getGreeting();
    assertEquals("Hello World!", greeting);

    // Assert
    WorkflowExecutorCache cache = factory.getCache();
    assertNotNull(cache);
    assertEquals(0, cache.size()); // removed from cache on completion

    // Verify the workflow succeeded without having to recover from a failure
    Map<String, String> tags =
        new ImmutableMap.Builder<String, String>(9)
            .putAll(MetricsTag.defaultTags(NAMESPACE))
            .put(MetricsTag.TASK_QUEUE, taskQueueName)
            .put(MetricsTag.WORKFLOW_TYPE, "GreetingSignalWorkflow")
            .build();
    metricsScope.close(); // Flush metrics
    reporter.assertCounter(MetricsType.STICKY_CACHE_HIT, tags, 1);
    reporter.assertNoMetric(MetricsType.STICKY_CACHE_MISS, tags);

    // Finish Workflow
    wrapper.close();
  }

  @Test
  public void workflowCacheEvictionDueToThreads() {
    // Arrange
    String taskQueueName = "workflowCacheEvictionDueToThreads";

    TestEnvironmentWrapper wrapper =
        new TestEnvironmentWrapper(
            WorkerFactoryOptions.newBuilder()
                .setMaxWorkflowThreadCount(10)
                .setWorkflowCacheSize(100)
                .build());
    WorkerFactory factory = wrapper.getWorkerFactory();
    Worker worker =
        factory.newWorker(
            taskQueueName,
            WorkerOptions.newBuilder().setMaxConcurrentWorkflowTaskExecutionSize(5).build());
    worker.registerWorkflowImplementationTypes(ActivitiesWorkflowImpl.class);
    worker.registerActivitiesImplementations(new ActivitiesImpl());
    factory.start();

    WorkflowOptions workflowOptions =
        WorkflowOptions.newBuilder()
            .setTaskQueue(taskQueueName)
            .setWorkflowRunTimeout(Duration.ofDays(30))
            .setWorkflowTaskTimeout(Duration.ofSeconds(1))
            .setWorkflowIdReusePolicy(
                WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
            .build();

    int count = 100;
    ActivitiesWorkflow[] workflows = new ActivitiesWorkflow[count];
    WorkflowParams w = new WorkflowParams();
    w.TemporalSleepMillis = 1000;
    w.ChainSequence = 2;
    w.ConcurrentCount = 1;
    w.PayloadSizeBytes = 10;
    w.TaskQueueName = taskQueueName;
    for (int i = 0; i < count; i++) {
      ActivitiesWorkflow workflow =
          wrapper.getWorkflowClient().newWorkflowStub(ActivitiesWorkflow.class, workflowOptions);
      workflows[i] = workflow;
      WorkflowClient.start(workflow::execute, w);
    }

    for (int i = 0; i < count; i++) {
      workflows[i].execute(w);
    }

    // Finish Workflow
    wrapper.close();
  }

  @Test
  public void whenStickyIsEnabledThenTheWorkflowIsCachedActivities() throws Exception {
    // Arrange
    String taskQueueName = "cachedStickyTest_Activities";

    TestEnvironmentWrapper wrapper =
        new TestEnvironmentWrapper(WorkerFactoryOptions.newBuilder().build());
    WorkerFactory factory = wrapper.getWorkerFactory();
    Worker worker = factory.newWorker(taskQueueName, WorkerOptions.newBuilder().build());
    worker.registerWorkflowImplementationTypes(ActivitiesWorkflowImpl.class);
    worker.registerActivitiesImplementations(new ActivitiesImpl());
    factory.start();

    WorkflowOptions workflowOptions =
        WorkflowOptions.newBuilder()
            .setTaskQueue(taskQueueName)
            .setWorkflowRunTimeout(Duration.ofDays(30))
            .setWorkflowTaskTimeout(Duration.ofSeconds(30))
            .build();
    ActivitiesWorkflow workflow =
        wrapper.getWorkflowClient().newWorkflowStub(ActivitiesWorkflow.class, workflowOptions);

    // Act
    WorkflowParams w = new WorkflowParams();
    w.TemporalSleepMillis = 1000;
    w.ChainSequence = 2;
    w.ConcurrentCount = 1;
    w.PayloadSizeBytes = 10;
    w.TaskQueueName = taskQueueName;
    workflow.execute(w);

    // Verify the workflow succeeded without having to recover from a failure
    Map<String, String> tags =
        new ImmutableMap.Builder<String, String>(9)
            .putAll(MetricsTag.defaultTags(NAMESPACE))
            .put(MetricsTag.TASK_QUEUE, taskQueueName)
            .put(MetricsTag.WORKFLOW_TYPE, "ActivitiesWorkflow")
            .build();
    metricsScope.close(); // Flush metrics
    reporter.assertCounter(MetricsType.STICKY_CACHE_HIT, tags, 4);
    reporter.assertNoMetric(MetricsType.STICKY_CACHE_MISS, tags);
    // Finish Workflow
    wrapper.close();
  }

  @Test
  @SuppressWarnings("deprecation")
  public void whenStickyIsEnabledThenTheWorkflowIsCachedChildWorkflows() throws Exception {
    // Arrange
    String taskQueueName = "cachedStickyTest_ChildWorkflows";

    TestEnvironmentWrapper wrapper =
        new TestEnvironmentWrapper(WorkerFactoryOptions.newBuilder().build());
    WorkerFactory factory = wrapper.getWorkerFactory();
    Worker worker = factory.newWorker(taskQueueName, WorkerOptions.newBuilder().build());
    worker.registerWorkflowImplementationTypes(
        GreetingParentWorkflowImpl.class, GreetingChildImpl.class);
    factory.start();

    WorkflowOptions workflowOptions =
        WorkflowOptions.newBuilder()
            .setTaskQueue(taskQueueName)
            .setWorkflowRunTimeout(Duration.ofDays(30))
            .setWorkflowTaskTimeout(Duration.ofSeconds(30))
            .build();
    TestWorkflow1 workflow =
        wrapper.getWorkflowClient().newWorkflowStub(TestWorkflow1.class, workflowOptions);

    // Act
    Assert.assertEquals("Hello World!", workflow.execute("World"));

    // Verify the workflow succeeded without having to recover from a failure
    Map<String, String> tags =
        new ImmutableMap.Builder<String, String>(9)
            .putAll(MetricsTag.defaultTags(NAMESPACE))
            .put(MetricsTag.TASK_QUEUE, taskQueueName)
            .put(MetricsTag.WORKFLOW_TYPE, "TestWorkflow1")
            .build();
    metricsScope.close(); // Flush metrics

    // making sure none workflow tasks came with a full history (after timeout from the sticky
    // queue) which caused a forced eviction from the cache. 1 eviction comes from the finishing of
    // the parent workflow and eviction of it from the cache.
    // TODO feel free to remove this assertion if refactoring out STICKY_CACHE_TOTAL_FORCED_EVICTION
    // metric. It has been added just as an additional verification to investigate a flaky test
    reporter.assertCounter(MetricsType.STICKY_CACHE_TOTAL_FORCED_EVICTION, tags, 1);

    reporter.assertNoMetric(MetricsType.STICKY_CACHE_MISS, tags);
    // It's valid for the server to schedule a workflow task
    //  - after the child is started and before the child is completed
    //  - or after it was completed
    // Depending on that, there will be one or two additional workflow tasks.
    // So both 1 or 2 here is a valid scenario. The main thing that matters is 0 cache miss.
    reporter.assertCounter(MetricsType.STICKY_CACHE_HIT, tags, a -> a == 1 || a == 2);
    // Finish Workflow
    wrapper.close();
  }

  @Test
  public void whenStickyIsEnabledThenTheWorkflowIsCachedMutableSideEffect() throws Exception {
    // Arrange
    String taskQueueName = "cachedStickyTest_MutableSideEffect";

    TestEnvironmentWrapper wrapper =
        new TestEnvironmentWrapper(WorkerFactoryOptions.newBuilder().build());
    WorkerFactory factory = wrapper.getWorkerFactory();
    Worker worker = factory.newWorker(taskQueueName, WorkerOptions.newBuilder().build());
    worker.registerWorkflowImplementationTypes(TestMutableSideEffectWorkflowImpl.class);
    factory.start();

    WorkflowOptions workflowOptions =
        WorkflowOptions.newBuilder()
            .setTaskQueue(taskQueueName)
            .setWorkflowRunTimeout(Duration.ofDays(30))
            .setWorkflowTaskTimeout(Duration.ofSeconds(30))
            .build();
    TestMutableSideEffectWorkflow workflow =
        wrapper
            .getWorkflowClient()
            .newWorkflowStub(TestMutableSideEffectWorkflow.class, workflowOptions);

    // Act
    ArrayDeque<Long> values = new ArrayDeque<>();
    values.add(1234L);
    values.add(1234L);
    values.add(123L); // expected to be ignored as it is smaller than 1234.
    values.add(3456L);
    mutableSideEffectValue.put(taskQueueName, values);
    String result = workflow.execute(taskQueueName);
    assertEquals("1234, 1234, 1234, 3456", result);

    // Verify the workflow succeeded without having to recover from a failure
    Map<String, String> tags =
        new ImmutableMap.Builder<String, String>(9)
            .putAll(MetricsTag.defaultTags(NAMESPACE))
            .put(MetricsTag.TASK_QUEUE, taskQueueName)
            .put(MetricsTag.WORKFLOW_TYPE, "TestMutableSideEffectWorkflow")
            .build();
    metricsScope.close(); // Flush metrics
    reporter.assertCounter(MetricsType.STICKY_CACHE_HIT, tags, 1);
    reporter.assertNoMetric(MetricsType.STICKY_CACHE_MISS, tags);
    // Finish Workflow
    wrapper.close();
  }

  @Test
  public void whenCacheIsEvictedTheWorkerCanRecover() {
    // Arrange
    String taskQueueName = "evictedStickyTest";
    TestEnvironmentWrapper wrapper =
        new TestEnvironmentWrapper(WorkerFactoryOptions.newBuilder().build());
    WorkerFactory factory = wrapper.getWorkerFactory();
    Worker worker = factory.newWorker(taskQueueName);
    worker.registerWorkflowImplementationTypes(GreetingSignalWorkflowImpl.class);
    factory.start();

    WorkflowOptions workflowOptions =
        WorkflowOptions.newBuilder()
            .setTaskQueue(taskQueueName)
            .setWorkflowRunTimeout(Duration.ofDays(30))
            .setWorkflowTaskTimeout(Duration.ofSeconds(30))
            .setWorkflowIdReusePolicy(
                WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
            .build();
    GreetingSignalWorkflow workflow =
        wrapper.getWorkflowClient().newWorkflowStub(GreetingSignalWorkflow.class, workflowOptions);

    // Act
    WorkflowClient.start(workflow::getGreeting);

    SDKTestWorkflowRule.waitForOKQuery(
        WorkflowStub.fromTyped(workflow)); // Wait for workflow to start

    WorkflowExecutorCache cache = factory.getCache();
    assertNotNull(cache);
    assertEquals(1, cache.size());
    cache.invalidateAll();
    assertEquals(0, cache.size());

    workflow.waitForName("World");
    String greeting = workflow.getGreeting();

    // Assert
    assertEquals("Hello World!", greeting);

    wrapper.close();
  }

  @Test
  public void workflowsCanBeQueried() throws Exception {
    // Arrange
    String taskQueueName = "queryStickyTest";
    TestEnvironmentWrapper wrapper =
        new TestEnvironmentWrapper(WorkerFactoryOptions.newBuilder().build());
    WorkerFactory factory = wrapper.getWorkerFactory();
    Worker worker = factory.newWorker(taskQueueName);
    worker.registerWorkflowImplementationTypes(GreetingSignalWorkflowImpl.class);
    factory.start();

    WorkflowOptions workflowOptions =
        WorkflowOptions.newBuilder()
            .setTaskQueue(taskQueueName)
            .setWorkflowRunTimeout(Duration.ofDays(30))
            .setWorkflowTaskTimeout(Duration.ofSeconds(30))
            .setWorkflowIdReusePolicy(
                WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
            .build();
    GreetingSignalWorkflow workflow =
        wrapper.getWorkflowClient().newWorkflowStub(GreetingSignalWorkflow.class, workflowOptions);

    // Act
    WorkflowClient.start(workflow::getGreeting);

    // Wait for the first workflow task to go through
    WorkflowExecutorCache cache = factory.getCache();
    assertNotNull(cache);
    long start = System.currentTimeMillis();
    while (cache.size() == 0 && System.currentTimeMillis() - start < 5000) {
      Thread.sleep(200);
    }
    assertEquals(1, cache.size());

    // Assert
    assertEquals(workflow.getProgress(), GreetingSignalWorkflow.Status.WAITING_FOR_NAME);

    workflow.waitForName("World");
    String greeting = workflow.getGreeting();

    assertEquals("Hello World!", greeting);
    assertEquals(workflow.getProgress(), GreetingSignalWorkflow.Status.GREETING_GENERATED);

    wrapper.close();
  }

  @Test
  public void workflowsCanBeQueriedAfterEviction() {
    // Arrange
    String taskQueueName = "queryAfterEvictionStickyTest";
    TestEnvironmentWrapper wrapper =
        new TestEnvironmentWrapper(WorkerFactoryOptions.newBuilder().build());
    WorkerFactory factory = wrapper.getWorkerFactory();
    Worker worker = factory.newWorker(taskQueueName);
    worker.registerWorkflowImplementationTypes(GreetingSignalWorkflowImpl.class);
    factory.start();

    WorkflowOptions workflowOptions =
        WorkflowOptions.newBuilder()
            .setTaskQueue(taskQueueName)
            .setWorkflowRunTimeout(Duration.ofDays(30))
            .setWorkflowTaskTimeout(Duration.ofSeconds(30))
            .setWorkflowIdReusePolicy(
                WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
            .build();
    GreetingSignalWorkflow workflow =
        wrapper.getWorkflowClient().newWorkflowStub(GreetingSignalWorkflow.class, workflowOptions);

    // Act
    WorkflowExecution execution = WorkflowClient.start(workflow::getGreeting);

    SDKTestWorkflowRule.waitForOKQuery(
        WorkflowStub.fromTyped(workflow)); // Wait for workflow to start

    WorkflowExecutorCache cache = factory.getCache();
    assertNotNull(cache);
    assertEquals(1, cache.size());
    cache.invalidateAll();
    assertEquals(0, cache.size());

    // Assert
    assertEquals(workflow.getProgress(), GreetingSignalWorkflow.Status.WAITING_FOR_NAME);
    SDKTestWorkflowRule.assertNoHistoryEvent(
        wrapper.testEnv.getWorkflowClient().fetchHistory(execution.getWorkflowId()).getHistory(),
        EventType.EVENT_TYPE_WORKFLOW_TASK_FAILED);

    wrapper.close();
  }

  @Test
  public void workflowCanContinueExecutionAfterBeingEvictedAndQueried() {
    // Arrange
    String taskQueueName = "continueExecutionAfterEvictionAndQueryStickyTest";
    TestEnvironmentWrapper wrapper =
        new TestEnvironmentWrapper(WorkerFactoryOptions.newBuilder().build());
    WorkerFactory factory = wrapper.getWorkerFactory();
    Worker worker = factory.newWorker(taskQueueName);
    worker.registerWorkflowImplementationTypes(GreetingSignalWorkflowImpl.class);
    factory.start();

    WorkflowOptions workflowOptions =
        WorkflowOptions.newBuilder()
            .setTaskQueue(taskQueueName)
            .setWorkflowRunTimeout(Duration.ofDays(30))
            .setWorkflowTaskTimeout(Duration.ofSeconds(30))
            .setWorkflowIdReusePolicy(
                WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
            .build();
    GreetingSignalWorkflow workflow =
        wrapper.getWorkflowClient().newWorkflowStub(GreetingSignalWorkflow.class, workflowOptions);

    // Act
    WorkflowExecution execution = WorkflowClient.start(workflow::getGreeting);

    SDKTestWorkflowRule.waitForOKQuery(
        WorkflowStub.fromTyped(workflow)); // Wait for workflow to start

    WorkflowExecutorCache cache = factory.getCache();
    assertNotNull(cache);
    assertEquals(1, cache.size());
    cache.invalidateAll();
    assertEquals(0, cache.size());

    // Assert
    assertEquals(workflow.getProgress(), GreetingSignalWorkflow.Status.WAITING_FOR_NAME);

    // Signal and get result to make sure the workflow successfully completed
    workflow.waitForName("World");
    String greeting = workflow.getGreeting();
    assertEquals("Hello World!", greeting);

    // Query after completion
    assertEquals(workflow.getProgress(), GreetingSignalWorkflow.Status.GREETING_GENERATED);
    SDKTestWorkflowRule.assertNoHistoryEvent(
        wrapper.testEnv.getWorkflowClient().fetchHistory(execution.getWorkflowId()).getHistory(),
        EventType.EVENT_TYPE_WORKFLOW_TASK_FAILED);

    wrapper.close();
  }

  private class TestEnvironmentWrapper {

    private final TestWorkflowEnvironment testEnv;

    public TestEnvironmentWrapper(WorkerFactoryOptions options) {
      if (options == null) {
        options = WorkerFactoryOptions.newBuilder().build();
      }
      WorkflowClientOptions clientOptions =
          WorkflowClientOptions.newBuilder().setNamespace(NAMESPACE).build();
      TestEnvironmentOptions testOptions =
          TestEnvironmentOptions.newBuilder()
              .setMetricsScope(metricsScope)
              .setWorkflowClientOptions(clientOptions)
              .setWorkerFactoryOptions(options)
              .setUseExternalService(useExternalService)
              .setTarget(serviceAddress)
              .build();
      testEnv = TestWorkflowEnvironment.newInstance(testOptions);
    }

    private WorkerFactory getWorkerFactory() {
      return testEnv.getWorkerFactory();
    }

    private WorkflowClient getWorkflowClient() {
      return testEnv.getWorkflowClient();
    }

    private void close() {
      testEnv.close();
    }
  }

  public static class WorkflowParams {

    public int ChainSequence;
    public int ConcurrentCount;
    public String TaskQueueName;
    public int PayloadSizeBytes;
    public long TemporalSleepMillis;
  }

  @WorkflowInterface
  public interface GreetingSignalWorkflow {
    /**
     * @return greeting string
     */
    @QueryMethod
    Status getProgress();

    /**
     * @return greeting string
     */
    @WorkflowMethod
    String getGreeting();

    /** Receives name through an external signal. */
    @SignalMethod
    void waitForName(String name);

    enum Status {
      WAITING_FOR_NAME,
      GREETING_GENERATED
    }
  }

  /** GreetingSignalWorkflow implementation that returns a greeting. */
  public static class GreetingSignalWorkflowImpl implements GreetingSignalWorkflow {

    private final CompletablePromise<String> name = Workflow.newPromise();
    private Status status = Status.WAITING_FOR_NAME;

    @Override
    public Status getProgress() {
      return status;
    }

    @Override
    public String getGreeting() {
      String greeting = "Hello " + name.get() + "!";
      status = Status.GREETING_GENERATED;
      return greeting;
    }

    @Override
    public void waitForName(String name) {
      this.name.complete(name);
    }
  }

  public static class GreetingParentWorkflowImpl implements TestWorkflow1 {

    @Override
    public String execute(String name) {
      // Workflows are stateful. So a new stub must be created for each new child.
      TestWorkflow2 child = Workflow.newChildWorkflowStub(TestWorkflow2.class);

      // This is a blocking call that returns only after the child has completed.
      Promise<String> greeting = Async.function(child::execute, "Hello", name);
      // Do something else here.
      return greeting.get(); // blocks waiting for the child to complete.
    }
  }

  public static class GreetingChildImpl implements TestWorkflow2 {
    @Override
    public String execute(String greeting, String name) {
      return greeting + " " + name + "!";
    }
  }

  @WorkflowInterface
  public interface ActivitiesWorkflow {

    @WorkflowMethod()
    void execute(WorkflowParams params);
  }

  public static class ActivitiesWorkflowImpl implements ActivitiesWorkflow {

    @Override
    public void execute(WorkflowParams params) {
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

        Workflow.sleep(params.TemporalSleepMillis);
      }
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
      log.info("sleep called");
    }
  }

  @WorkflowInterface
  public interface TestMutableSideEffectWorkflow {

    @WorkflowMethod
    String execute(String taskQueue);
  }

  private static final Map<String, Queue<Long>> mutableSideEffectValue =
      Collections.synchronizedMap(new HashMap<>());

  public static class TestMutableSideEffectWorkflowImpl implements TestMutableSideEffectWorkflow {

    @Override
    public String execute(String taskQueue) {
      StringBuilder result = new StringBuilder();
      for (int i = 0; i < 4; i++) {
        long value =
            Workflow.mutableSideEffect(
                "id1",
                Long.class,
                (o, n) -> n > o,
                () -> mutableSideEffectValue.get(taskQueue).poll());
        if (result.length() > 0) {
          result.append(", ");
        }
        result.append(value);
        // Sleep is here to ensure that mutableSideEffect works when replaying a history.
        if (i >= 3) {
          Workflow.sleep(Duration.ofSeconds(1));
        }
      }
      return result.toString();
    }
  }
}
