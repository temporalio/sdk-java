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

package com.uber.cadence.testing;

import com.google.common.annotations.VisibleForTesting;
import com.uber.cadence.converter.DataConverter;
import com.uber.cadence.converter.JsonDataConverter;
import com.uber.cadence.internal.metrics.NoopScope;
import com.uber.cadence.worker.Worker;
import com.uber.cadence.workflow.WorkflowInterceptor;
import com.uber.m3.tally.Scope;
import java.util.Objects;
import java.util.function.Function;

@VisibleForTesting
public final class TestEnvironmentOptions {

  public static final class Builder {

    private DataConverter dataConverter = JsonDataConverter.getInstance();

    private String domain = "unit-test";

    private Function<WorkflowInterceptor, WorkflowInterceptor> interceptorFactory = (n) -> n;

    private Scope metricsScope;

    private boolean enableLoggingInReplay;

    private Worker.FactoryOptions factoryOptions;

    /** Sets data converter to use for unit-tests. Default is {@link JsonDataConverter}. */
    public Builder setDataConverter(DataConverter dataConverter) {
      this.dataConverter = Objects.requireNonNull(dataConverter);
      return this;
    }

    /** Set domain to use for test workflows. Optional. Default is "unit-test" */
    public Builder setDomain(String domain) {
      this.domain = Objects.requireNonNull(domain);
      return this;
    }

    /**
     * Specifies an interceptor factory that creates interceptors for workflow calls like activity
     * invocations. Note that the factory is called for each decision and must return a new object
     * instance every time it is called.
     */
    public Builder setInterceptorFactory(
        Function<WorkflowInterceptor, WorkflowInterceptor> interceptorFactory) {
      this.interceptorFactory = Objects.requireNonNull(interceptorFactory);
      return this;
    }

    /**
     * Set scope to use for metrics reporting. Optional. Default is noop scope that skips reporting.
     */
    public Builder setMetricsScope(Scope metricsScope) {
      this.metricsScope = metricsScope;
      return this;
    }

    /** Set factoryOptions for worker factory used to create workers. */
    public Builder setFactoryOptions(Worker.FactoryOptions options) {
      this.factoryOptions = options;
      return this;
    }

    /** Set whether to log during decision replay. */
    public Builder setEnableLoggingInReplay(boolean enableLoggingInReplay) {
      this.enableLoggingInReplay = enableLoggingInReplay;
      return this;
    }

    public TestEnvironmentOptions build() {
      if (metricsScope == null) {
        metricsScope = NoopScope.getInstance();
      }

      if (factoryOptions == null) {
        factoryOptions = new Worker.FactoryOptions.Builder().Build();
      }

      return new TestEnvironmentOptions(
          dataConverter,
          domain,
          interceptorFactory,
          metricsScope,
          factoryOptions,
          enableLoggingInReplay);
    }
  }

  private final DataConverter dataConverter;
  private final String domain;
  private final Function<WorkflowInterceptor, WorkflowInterceptor> interceptorFactory;
  private final Scope metricsScope;
  private final boolean enableLoggingInReplay;
  private final Worker.FactoryOptions workerFactoryOptions;

  private TestEnvironmentOptions(
      DataConverter dataConverter,
      String domain,
      Function<WorkflowInterceptor, WorkflowInterceptor> interceptorFactory,
      Scope metricsScope,
      Worker.FactoryOptions options,
      boolean enableLoggingInReplay) {
    this.dataConverter = dataConverter;
    this.domain = domain;
    this.interceptorFactory = interceptorFactory;
    this.metricsScope = metricsScope;
    this.workerFactoryOptions = options;
    this.enableLoggingInReplay = enableLoggingInReplay;
  }

  public DataConverter getDataConverter() {
    return dataConverter;
  }

  public String getDomain() {
    return domain;
  }

  public Function<WorkflowInterceptor, WorkflowInterceptor> getInterceptorFactory() {
    return interceptorFactory;
  }

  public Scope getMetricsScope() {
    return metricsScope;
  }

  public boolean isLoggingEnabledInReplay() {
    return enableLoggingInReplay;
  }

  public Worker.FactoryOptions getWorkerFactoryOptions() {
    return workerFactoryOptions;
  }

  @Override
  public String toString() {
    return "TestEnvironmentOptions{"
        + "dataConverter="
        + dataConverter
        + ", domain='"
        + domain
        + '\''
        + '}';
  }
}
