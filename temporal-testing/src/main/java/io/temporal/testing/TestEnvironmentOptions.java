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
import io.temporal.client.WorkflowClientOptions;
import io.temporal.worker.WorkerFactoryOptions;
import java.time.Instant;

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

    private Scope metricsScope;

    private boolean useExternalService;

    private String target;

    private long initialTimeMillis;

    private Builder() {}

    private Builder(TestEnvironmentOptions o) {
      workerFactoryOptions = o.workerFactoryOptions;
      workflowClientOptions = o.workflowClientOptions;
      useExternalService = o.useExternalService;
      target = o.target;
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

    public TestEnvironmentOptions build() {
      return new TestEnvironmentOptions(
          workflowClientOptions,
          workerFactoryOptions,
          useExternalService,
          target,
          initialTimeMillis,
          metricsScope);
    }

    public TestEnvironmentOptions validateAndBuildWithDefaults() {
      return new TestEnvironmentOptions(
          WorkflowClientOptions.newBuilder(workflowClientOptions).validateAndBuildWithDefaults(),
          WorkerFactoryOptions.newBuilder(workerFactoryOptions).validateAndBuildWithDefaults(),
          useExternalService,
          target,
          initialTimeMillis,
          metricsScope == null ? new NoopScope() : metricsScope);
    }
  }

  private final WorkerFactoryOptions workerFactoryOptions;
  private final WorkflowClientOptions workflowClientOptions;
  private final Scope metricsScope;
  private final boolean useExternalService;
  private final String target;
  private final long initialTimeMillis;

  private TestEnvironmentOptions(
      WorkflowClientOptions workflowClientOptions,
      WorkerFactoryOptions workerFactoryOptions,
      boolean useExternalService,
      String target,
      long initialTimeMillis,
      Scope metricsScope) {
    this.workflowClientOptions = workflowClientOptions;
    this.workerFactoryOptions = workerFactoryOptions;
    this.metricsScope = metricsScope;
    this.useExternalService = useExternalService;
    this.target = target;
    this.initialTimeMillis = initialTimeMillis;
  }

  public WorkerFactoryOptions getWorkerFactoryOptions() {
    return workerFactoryOptions;
  }

  public WorkflowClientOptions getWorkflowClientOptions() {
    return workflowClientOptions;
  }

  public Scope getMetricsScope() {
    return metricsScope;
  }

  public boolean isUseExternalService() {
    return useExternalService;
  }

  public String getTarget() {
    return target;
  }

  public long getInitialTimeMillis() {
    return initialTimeMillis;
  }

  @Override
  public String toString() {
    return "TestEnvironmentOptions{"
        + "workerFactoryOptions="
        + workerFactoryOptions
        + ", workflowClientOptions="
        + workflowClientOptions
        + ", useExternalService="
        + useExternalService
        + ", target="
        + target
        + ", initialTimeMillis="
        + initialTimeMillis
        + '}';
  }
}
