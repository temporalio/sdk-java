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

import com.google.common.annotations.VisibleForTesting;
import com.uber.m3.tally.NoopScope;
import com.uber.m3.tally.Scope;
import io.temporal.api.enums.v1.IndexedValueType;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.WorkerFactoryOptions;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nonnull;

@VisibleForTesting
public final class TestEnvironmentOptions {

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(TestEnvironmentOptions options) {
    return new Builder(options);
  }

  public static TestEnvironmentOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final TestEnvironmentOptions DEFAULT_INSTANCE;

  static {
    DEFAULT_INSTANCE = TestEnvironmentOptions.newBuilder().build();
  }

  public static final class Builder {

    private WorkerFactoryOptions workerFactoryOptions;

    private WorkflowClientOptions workflowClientOptions;

    private WorkflowServiceStubsOptions workflowServiceStubsOptions;

    private Scope metricsScope;

    private boolean useExternalService;

    private String target;

    private long initialTimeMillis;

    private boolean useTimeskipping = true;

    private Map<String, IndexedValueType> searchAttributes = new HashMap<>();

    private Builder() {}

    private Builder(TestEnvironmentOptions o) {
      workerFactoryOptions = o.workerFactoryOptions;
      workflowClientOptions = o.workflowClientOptions;
      useExternalService = o.useExternalService;
      target = o.target;
      useTimeskipping = o.useTimeskipping;
    }

    public Builder setWorkflowClientOptions(WorkflowClientOptions workflowClientOptions) {
      this.workflowClientOptions = workflowClientOptions;
      return this;
    }

    /** Set factoryOptions for worker factory used to create workers. */
    public Builder setWorkerFactoryOptions(WorkerFactoryOptions options) {
      this.workerFactoryOptions = options;
      return this;
    }

    public Builder setWorkflowServiceStubsOptions(WorkflowServiceStubsOptions options) {
      this.workflowServiceStubsOptions = options;
      return this;
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

    /**
     * Set to true in order to make test environment use external temporal service or false for
     * in-memory test implementation.
     */
    public Builder setUseExternalService(boolean useExternalService) {
      this.useExternalService = useExternalService;
      return this;
    }

    /**
     * Optional parameter that defines an endpoint which will be used for the communication with
     * standalone temporal service. Has no effect if {@link #useExternalService} is set to false.
     *
     * <p>Defaults to 127.0.0.1:7233
     */
    public Builder setTarget(String target) {
      this.target = target;
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
     * Sets whether the TestWorkflowEnvironment will timeskip. If true, no actual wall-clock time
     * will pass when a workflow sleeps or sets a timer.
     *
     * <p>Default is true
     */
    public Builder setUseTimeskipping(boolean useTimeskipping) {
      this.useTimeskipping = useTimeskipping;
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
     * This method is not made public and for framework code only, users are encouraged to use a
     * better structured {@link #registerSearchAttribute(String, IndexedValueType)}
     */
    Builder setSearchAttributes(@Nonnull Map<String, IndexedValueType> searchAttributes) {
      this.searchAttributes = new HashMap<>(searchAttributes);
      return this;
    }

    public TestEnvironmentOptions build() {
      return new TestEnvironmentOptions(
          workflowClientOptions,
          workerFactoryOptions,
          workflowServiceStubsOptions,
          useExternalService,
          target,
          initialTimeMillis,
          metricsScope,
          useTimeskipping,
          searchAttributes);
    }

    public TestEnvironmentOptions validateAndBuildWithDefaults() {
      return new TestEnvironmentOptions(
          (workflowClientOptions != null
                  ? WorkflowClientOptions.newBuilder(workflowClientOptions)
                  : WorkflowClientOptions.newBuilder())
              .validateAndBuildWithDefaults(),
          (workerFactoryOptions != null
                  ? WorkerFactoryOptions.newBuilder(workerFactoryOptions)
                  : WorkerFactoryOptions.newBuilder())
              .validateAndBuildWithDefaults(),
          (workflowServiceStubsOptions != null
                  ? WorkflowServiceStubsOptions.newBuilder(workflowServiceStubsOptions)
                  : WorkflowServiceStubsOptions.newBuilder())
              .validateAndBuildWithDefaults(),
          useExternalService,
          target,
          initialTimeMillis,
          metricsScope == null ? new NoopScope() : metricsScope,
          useTimeskipping,
          searchAttributes);
    }
  }

  private final WorkerFactoryOptions workerFactoryOptions;
  private final WorkflowClientOptions workflowClientOptions;
  private final WorkflowServiceStubsOptions workflowServiceStubsOptions;
  private final Scope metricsScope;
  private final boolean useExternalService;
  private final String target;
  private final long initialTimeMillis;
  private final boolean useTimeskipping;
  @Nonnull private final Map<String, IndexedValueType> searchAttributes;

  private TestEnvironmentOptions(
      WorkflowClientOptions workflowClientOptions,
      WorkerFactoryOptions workerFactoryOptions,
      WorkflowServiceStubsOptions workflowServiceStubsOptions,
      boolean useExternalService,
      String target,
      long initialTimeMillis,
      Scope metricsScope,
      boolean useTimeskipping,
      @Nonnull Map<String, IndexedValueType> searchAttributes) {
    this.workflowClientOptions = workflowClientOptions;
    this.workerFactoryOptions = workerFactoryOptions;
    this.workflowServiceStubsOptions = workflowServiceStubsOptions;
    this.metricsScope = metricsScope;
    this.useExternalService = useExternalService;
    this.target = target;
    this.initialTimeMillis = initialTimeMillis;
    this.useTimeskipping = useTimeskipping;
    this.searchAttributes = searchAttributes;
  }

  public WorkerFactoryOptions getWorkerFactoryOptions() {
    return workerFactoryOptions;
  }

  public WorkflowClientOptions getWorkflowClientOptions() {
    return workflowClientOptions;
  }

  public WorkflowServiceStubsOptions getWorkflowServiceStubsOptions() {
    return workflowServiceStubsOptions;
  }

  public Scope getMetricsScope() {
    return metricsScope;
  }

  public boolean isUseExternalService() {
    return useExternalService;
  }

  public boolean isUseTimeskipping() {
    return useTimeskipping;
  }

  public String getTarget() {
    return target;
  }

  public long getInitialTimeMillis() {
    return initialTimeMillis;
  }

  @Nonnull
  public Map<String, IndexedValueType> getSearchAttributes() {
    return searchAttributes;
  }

  @Override
  public String toString() {
    return "TestEnvironmentOptions{"
        + "workerFactoryOptions="
        + workerFactoryOptions
        + ", workflowClientOptions="
        + workflowClientOptions
        + ", workflowServiceStubsOptions="
        + workflowServiceStubsOptions
        + ", metricsScope="
        + metricsScope
        + ", useExternalService="
        + useExternalService
        + ", target="
        + target
        + ", initialTimeMillis="
        + initialTimeMillis
        + ", searchAttributes="
        + searchAttributes
        + '}';
  }
}
