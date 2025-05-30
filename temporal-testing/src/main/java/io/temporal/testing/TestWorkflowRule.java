package io.temporal.testing;

import static io.temporal.testing.internal.TestServiceUtils.applyNexusServiceOptions;

import com.uber.m3.tally.Scope;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.IndexedValueType;
import io.temporal.api.history.v1.History;
import io.temporal.api.nexus.v1.Endpoint;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.SearchAttributeKey;
import io.temporal.common.interceptors.WorkerInterceptor;
import io.temporal.internal.common.env.DebugModeUtils;
import io.temporal.internal.docker.RegisterTestNamespace;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.*;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.rules.TestWatcher;
import org.junit.rules.Timeout;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

/**
 * JUnit4
 *
 * <p>Test rule that sets up test environment, simplifying workflow worker creation and shutdown.
 * Can be used with both in-memory and standalone temporal service. (see {@link
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

  private final String namespace;
  private final boolean useExternalService;
  private final boolean doNotStart;
  private final boolean doNotSetupNexusEndpoint;
  @Nullable private final Timeout globalTimeout;

  private final Class<?>[] workflowTypes;
  private final Object[] activityImplementations;
  private final Object[] nexusServiceImplementations;
  private final WorkflowServiceStubsOptions serviceStubsOptions;
  private final WorkflowClientOptions clientOptions;
  private final WorkerFactoryOptions workerFactoryOptions;
  private final WorkflowImplementationOptions workflowImplementationOptions;
  private final WorkerOptions workerOptions;
  private final String target;
  private final boolean useTimeskipping;
  private final Scope metricsScope;
  private String uniquePostfix;

  @Nonnull private final Map<String, IndexedValueType> searchAttributes;

  private String taskQueue;
  private String nexusEndpointName;
  private Endpoint nexusEndpoint;
  private final TestWorkflowEnvironment testEnvironment;
  private final TestWatcher watchman =
      new TestWatcher() {
        @Override
        protected void failed(Throwable e, Description description) {
          System.err.println("WORKFLOW EXECUTION HISTORIES:\n" + testEnvironment.getDiagnostics());
        }
      };

  private TestWorkflowRule(Builder builder) {
    this.doNotStart = builder.doNotStart;
    this.doNotSetupNexusEndpoint = builder.doNotSetupNexusEndpoint;
    this.useExternalService = builder.useExternalService;
    this.namespace =
        (builder.namespace == null) ? RegisterTestNamespace.NAMESPACE : builder.namespace;
    this.workflowTypes = (builder.workflowTypes == null) ? new Class[0] : builder.workflowTypes;
    this.activityImplementations =
        (builder.activityImplementations == null) ? new Object[0] : builder.activityImplementations;
    this.nexusServiceImplementations =
        (builder.nexusServiceImplementations == null)
            ? new Object[0]
            : builder.nexusServiceImplementations;
    this.serviceStubsOptions =
        (builder.workflowServiceStubsOptions == null)
            ? WorkflowServiceStubsOptions.newBuilder().build()
            : builder.workflowServiceStubsOptions;
    this.clientOptions =
        (builder.workflowClientOptions == null)
            ? WorkflowClientOptions.newBuilder().setNamespace(namespace).build()
            : builder.workflowClientOptions.toBuilder().setNamespace(namespace).build();
    this.workerOptions =
        (builder.workerOptions == null)
            ? WorkerOptions.newBuilder().build()
            : builder.workerOptions;
    this.workerFactoryOptions =
        (builder.workerFactoryOptions == null)
            ? WorkerFactoryOptions.newBuilder().build()
            : builder.workerFactoryOptions;
    this.workflowImplementationOptions =
        (builder.workflowImplementationOptions == null)
            ? WorkflowImplementationOptions.newBuilder().build()
            : builder.workflowImplementationOptions;
    this.globalTimeout =
        !DebugModeUtils.isTemporalDebugModeOn() && builder.testTimeoutSeconds != 0
            ? Timeout.seconds(builder.testTimeoutSeconds)
            : null;

    this.target = builder.target;
    this.useTimeskipping = builder.useTimeskipping;
    this.metricsScope = builder.metricsScope;
    this.searchAttributes = builder.searchAttributes;

    this.testEnvironment =
        TestWorkflowEnvironment.newInstance(createTestEnvOptions(builder.initialTimeMillis));
  }

  protected TestEnvironmentOptions createTestEnvOptions(long initialTimeMillis) {
    return TestEnvironmentOptions.newBuilder()
        .setWorkflowServiceStubsOptions(serviceStubsOptions)
        .setWorkflowClientOptions(clientOptions)
        .setWorkerFactoryOptions(workerFactoryOptions)
        .setUseExternalService(useExternalService)
        .setUseTimeskipping(useTimeskipping)
        .setTarget(target)
        .setInitialTimeMillis(initialTimeMillis)
        .setMetricsScope(metricsScope)
        .setSearchAttributes(searchAttributes)
        .build();
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static class Builder {

    private String namespace;
    private String target;
    private boolean useExternalService;
    private boolean doNotStart;
    private boolean doNotSetupNexusEndpoint;
    private long initialTimeMillis;
    // Default to TestEnvironmentOptions isUseTimeskipping
    private boolean useTimeskipping =
        TestEnvironmentOptions.getDefaultInstance().isUseTimeskipping();

    private Class<?>[] workflowTypes;
    private Object[] activityImplementations;
    private Object[] nexusServiceImplementations;
    private WorkflowServiceStubsOptions workflowServiceStubsOptions;
    private WorkflowClientOptions workflowClientOptions;
    private WorkerFactoryOptions workerFactoryOptions;
    private WorkflowImplementationOptions workflowImplementationOptions;
    private WorkerOptions workerOptions;
    private long testTimeoutSeconds;
    @Nonnull private final Map<String, IndexedValueType> searchAttributes = new HashMap<>();
    private Scope metricsScope;

    protected Builder() {}

    public Builder setWorkerOptions(WorkerOptions options) {
      this.workerOptions = options;
      return this;
    }

    public void setWorkflowServiceStubsOptions(
        WorkflowServiceStubsOptions workflowServiceStubsOptions) {
      this.workflowServiceStubsOptions = workflowServiceStubsOptions;
    }

    /**
     * Override {@link WorkflowClientOptions} for test environment. If set, takes precedence over
     * {@link #setNamespace(String) namespace}.
     */
    public Builder setWorkflowClientOptions(WorkflowClientOptions workflowClientOptions) {
      this.workflowClientOptions = workflowClientOptions;
      return this;
    }

    public Builder setWorkerFactoryOptions(WorkerFactoryOptions options) {
      this.workerFactoryOptions = options;
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

    /**
     * Specify Nexus service implementations to register with the Temporal worker. If any Nexus
     * services are registered with the worker, the rule will automatically create a Nexus Endpoint
     * for the test and the endpoint will be set on the per-service options and default options in
     * {@link WorkflowImplementationOptions} if none are provided.
     *
     * <p>This can be disabled by setting {@link #setDoNotSetupNexusEndpoint(boolean)} to true.
     *
     * @see Worker#registerNexusServiceImplementation(Object...)
     */
    public Builder setNexusServiceImplementation(Object... nexusServiceImplementations) {
      this.nexusServiceImplementations = nexusServiceImplementations;
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
     * Sets TestEnvironmentOptions.setUseTimeskippings. If true, no actual wall-clock time will pass
     * when a workflow sleeps or sets a timer.
     *
     * <p>Default is true
     */
    public Builder setUseTimeskipping(boolean useTimeskipping) {
      this.useTimeskipping = useTimeskipping;
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

    /**
     * @deprecated Temporal test rule shouldn't be responsible for enforcing test timeouts. Use
     *     toolchain of your test framework to enforce timeouts.
     */
    @Deprecated
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

    /**
     * When set to true the {@link TestWorkflowEnvironment} will not automatically create a Nexus
     * Endpoint. This is useful when you want to manually create a Nexus Endpoint for your test.
     */
    public Builder setDoNotSetupNexusEndpoint(boolean doNotSetupNexusEndpoint) {
      this.doNotSetupNexusEndpoint = doNotSetupNexusEndpoint;
      return this;
    }

    /**
     * Add a search attribute to be registered on the Temporal Server.
     *
     * @param name name of the search attribute
     * @param type search attribute type
     * @return {@code this}
     * @see <a
     *     href="https://docs.temporal.io/docs/tctl/how-to-add-a-custom-search-attribute-to-a-cluster-using-tctl">Add
     *     a Custom Search Attribute Using tctl</a>
     */
    public Builder registerSearchAttribute(String name, IndexedValueType type) {
      this.searchAttributes.put(name, type);
      return this;
    }

    /**
     * Add a search attribute to be registered on the Temporal Server.
     *
     * @param key key to register
     * @return {@code this}
     * @see <a
     *     href="https://docs.temporal.io/docs/tctl/how-to-add-a-custom-search-attribute-to-a-cluster-using-tctl">Add
     *     a Custom Search Attribute Using tctl</a>
     */
    public Builder registerSearchAttribute(SearchAttributeKey<?> key) {
      return this.registerSearchAttribute(key.getName(), key.getValueType());
    }

    /**
     * Sets the scope to be used for metrics reporting. Optional. Default is to not report metrics.
     *
     * <p>Note: Don't mock {@link Scope} in tests! If you need to verify the metrics behavior,
     * create a real Scope and mock, stub or spy a reporter instance:<br>
     *
     * <pre>{@code
     * StatsReporter reporter = mock(StatsReporter.class);
     * Scope metricsScope =
     *     new RootScopeBuilder()
     *         .reporter(reporter)
     *         .reportEvery(com.uber.m3.util.Duration.ofMillis(10));
     * }</pre>
     *
     * @param metricsScope the scope to be used for metrics reporting.
     * @return {@code this}
     */
    public Builder setMetricsScope(Scope metricsScope) {
      this.metricsScope = metricsScope;
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

    Test annotation = description.getAnnotation(Test.class);
    boolean timeoutIsOverriddenOnTestAnnotation = annotation != null && annotation.timeout() > 0;

    if (globalTimeout != null && !timeoutIsOverriddenOnTestAnnotation) {
      testWorkflowStatement = globalTimeout.apply(testWorkflowStatement, description);
    }

    return watchman.apply(testWorkflowStatement, description);
  }

  private String init(Description description) {
    uniquePostfix = description.getMethodName() + "-" + UUID.randomUUID();
    String taskQueue = "WorkflowTest-" + uniquePostfix;
    nexusEndpointName = String.format("WorkflowTestNexusEndpoint-%s", UUID.randomUUID());

    WorkerOptions workerOptions = this.workerOptions;
    Worker worker = testEnvironment.newWorker(taskQueue, workerOptions);
    WorkflowImplementationOptions workflowImplementationOptions =
        this.workflowImplementationOptions;
    if (!doNotSetupNexusEndpoint) {
      workflowImplementationOptions =
          applyNexusServiceOptions(
              workflowImplementationOptions, nexusServiceImplementations, nexusEndpointName);
    }
    worker.registerWorkflowImplementationTypes(workflowImplementationOptions, workflowTypes);
    worker.registerActivitiesImplementations(activityImplementations);
    worker.registerNexusServiceImplementation(nexusServiceImplementations);
    return taskQueue;
  }

  private void start() {
    if (!doNotSetupNexusEndpoint && nexusServiceImplementations.length > 0) {
      nexusEndpoint = testEnvironment.createNexusEndpoint(nexusEndpointName, taskQueue);
    }
    if (!doNotStart) {
      testEnvironment.start();
    }
  }

  protected void shutdown() {
    if (nexusEndpoint != null && !testEnvironment.getOperatorServiceStubs().isShutdown()) {
      testEnvironment.deleteNexusEndpoint(nexusEndpoint);
    }
    testEnvironment.close();
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

  /**
   * @return name of the task queue that test worker is polling.
   */
  public String getTaskQueue() {
    return taskQueue;
  }

  /**
   * @return The options used for the worker.
   */
  public WorkerOptions getWorkerOptions() {
    return workerOptions;
  }

  /**
   * @return endpoint of the nexus service created for the test.
   */
  public Endpoint getNexusEndpoint() {
    return nexusEndpoint;
  }

  /**
   * @return client to the Temporal service used to start and query workflows.
   */
  public WorkflowClient getWorkflowClient() {
    return testEnvironment.getWorkflowClient();
  }

  /**
   * @return stubs connected to the test server (in-memory or external)
   */
  public WorkflowServiceStubs getWorkflowServiceStubs() {
    return testEnvironment.getWorkflowServiceStubs();
  }

  /**
   * @return blockingStub
   */
  public WorkflowServiceGrpc.WorkflowServiceBlockingStub blockingStub() {
    return getWorkflowServiceStubs().blockingStub();
  }

  /**
   * @return tracer.
   */
  public <T extends WorkerInterceptor> T getInterceptor(Class<T> type) {
    if (workerFactoryOptions.getWorkerInterceptors() != null) {
      for (WorkerInterceptor interceptor : workerFactoryOptions.getWorkerInterceptors()) {
        if (type.isInstance(interceptor)) {
          return type.cast(interceptor);
        }
      }
    }
    return null;
  }

  /**
   * @return workflow execution history
   * @deprecated use {@link WorkflowClient#fetchHistory(String, String)}. To obtain a WorkflowClient
   *     use {@link #getWorkflowClient()}
   */
  @Deprecated
  public History getHistory(@Nonnull WorkflowExecution execution) {
    return testEnvironment.getWorkflowExecutionHistory(execution).getHistory();
  }

  /**
   * @return name of the task queue that test worker is polling.
   * @deprecated use {@link WorkflowClient#fetchHistory(String, String)}. To obtain a WorkflowClient
   *     use {@link #getWorkflowClient()}
   */
  @Deprecated
  public History getWorkflowExecutionHistory(WorkflowExecution execution) {
    return testEnvironment.getWorkflowExecutionHistory(execution).getHistory();
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

  public WorkerFactoryOptions getWorkerFactoryOptions() {
    return workerFactoryOptions;
  }

  public <T> T newWorkflowStub(Class<T> workflow) {
    return getWorkflowClient()
        .newWorkflowStub(workflow, newWorkflowOptionsForTaskQueue(getTaskQueue()));
  }

  public WorkflowStub newUntypedWorkflowStub(String workflow) {
    return getWorkflowClient()
        .newUntypedWorkflowStub(workflow, newWorkflowOptionsForTaskQueue(getTaskQueue()));
  }

  /**
   * @return A unique string containing the test name appended to the task queue used by the test
   *     worker. Can be used for other test-specific naming.
   */
  public String getUniquePostfix() {
    return uniquePostfix;
  }

  private static WorkflowOptions newWorkflowOptionsForTaskQueue(String taskQueue) {
    return WorkflowOptions.newBuilder().setTaskQueue(taskQueue).build();
  }
}
