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
import io.temporal.client.WorkflowClientOptions;
import io.temporal.context.ContextPropagator;
import io.temporal.worker.WorkerFactoryOptions;
import java.util.Arrays;
import java.util.List;

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

  private static final List<ContextPropagator> EMPTY_CONTEXT_PROPAGATORS = Arrays.asList();

  public static final class Builder {

    private WorkerFactoryOptions workerFactoryOptions;

    private WorkflowClientOptions workflowClientOptions;

    private Builder() {}

    private Builder(TestEnvironmentOptions o) {
      workerFactoryOptions = o.workerFactoryOptions;
      workflowClientOptions = o.workflowClientOptions;
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

    public TestEnvironmentOptions build() {
      return new TestEnvironmentOptions(workflowClientOptions, workerFactoryOptions);
    }

    public TestEnvironmentOptions validateAndBuildWithDefaults() {
      return new TestEnvironmentOptions(
          workflowClientOptions == null
              ? WorkflowClientOptions.newBuilder().validateAndBuildWithDefaults()
              : workflowClientOptions,
          workerFactoryOptions == null
              ? WorkerFactoryOptions.newBuilder().validateAndBuildWithDefaults()
              : workerFactoryOptions);
    }
  }

  private final WorkerFactoryOptions workerFactoryOptions;
  private final WorkflowClientOptions workflowClientOptions;

  private TestEnvironmentOptions(
      WorkflowClientOptions workflowClientOptions, WorkerFactoryOptions workerFactoryOptions) {
    this.workflowClientOptions = workflowClientOptions;
    this.workerFactoryOptions = workerFactoryOptions;
  }

  public WorkerFactoryOptions getWorkerFactoryOptions() {
    return workerFactoryOptions;
  }

  public WorkflowClientOptions getWorkflowClientOptions() {
    return workflowClientOptions;
  }

  @Override
  public String toString() {
    return "TestEnvironmentOptions{"
        + "workerFactoryOptions="
        + workerFactoryOptions
        + ", workflowClientOptions="
        + workflowClientOptions
        + '}';
  }
}
