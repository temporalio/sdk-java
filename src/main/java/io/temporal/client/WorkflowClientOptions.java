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

package io.temporal.client;

import com.uber.m3.tally.Scope;
import io.temporal.converter.DataConverter;
import io.temporal.converter.JsonDataConverter;
import io.temporal.internal.metrics.NoopScope;
import java.lang.management.ManagementFactory;
import java.util.Objects;

/** Options for WorkflowClient configuration. */
public final class WorkflowClientOptions {

  private static final WorkflowClientOptions DEFAULT_INSTANCE;
  private static final String DEFAULT_DOMAIN = "default";

  static {
    DEFAULT_INSTANCE = newBuilder().build();
  }

  public static Builder newBuilder() {
    return new WorkflowClientOptions.Builder();
  }

  public static Builder newBuilder(WorkflowClientOptions options) {
    return new WorkflowClientOptions.Builder(options);
  }

  public static WorkflowClientOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  public static final class Builder {

    private String domain;
    private DataConverter dataConverter;
    private WorkflowClientInterceptor[] interceptors;
    private Scope metricsScope;
    private String identity;

    public Builder() {}

    public Builder(WorkflowClientOptions options) {
      domain = options.domain;
      dataConverter = options.dataConverter;
      interceptors = options.interceptors;
      metricsScope = options.metricsScope;
    }

    public Builder setDomain(String domain) {
      this.domain = domain;
      return this;
    }

    /**
     * Overrides a data converter implementation used serialize workflow and activity arguments and
     * results.
     *
     * <p>Default is {@link io.temporal.converter.JsonDataConverter} data converter.
     */
    public Builder setDataConverter(DataConverter dataConverter) {
      this.dataConverter = Objects.requireNonNull(dataConverter);
      return this;
    }

    /**
     * Interceptor used to intercept workflow client calls.
     *
     * @param interceptors not null
     */
    public Builder setInterceptors(WorkflowClientInterceptor... interceptors) {
      this.interceptors = Objects.requireNonNull(interceptors);
      return this;
    }

    /**
     * Sets the scope to be used for the workflow client for metrics reporting.
     *
     * @param metricsScope the scope to be used. Not null.
     */
    public Builder setMetricsScope(Scope metricsScope) {
      this.metricsScope = Objects.requireNonNull(metricsScope);
      return this;
    }

    /**
     * Override human readable identity of the worker. Identity is used to identify a worker and is
     * recorded in the workflow history events. For example when a worker gets an activity task the
     * correspondent ActivityTaskStarted event contains the worker identity as a field. Default is
     * whatever <code>(ManagementFactory.getRuntimeMXBean().getName()</code> returns.
     *
     * @return
     */
    public Builder setIdentity(String identity) {
      this.identity = identity;
      return this;
    }

    public WorkflowClientOptions build() {
      return new WorkflowClientOptions(domain, dataConverter, interceptors, metricsScope, identity);
    }

    public WorkflowClientOptions validateAndBuildWithDefaults() {
      return new WorkflowClientOptions(
          domain == null ? DEFAULT_DOMAIN : domain,
          dataConverter == null ? JsonDataConverter.getInstance() : dataConverter,
          interceptors == null ? EMPTY_INTERCEPTOR_ARRAY : interceptors,
          metricsScope == null ? NoopScope.getInstance() : metricsScope,
          identity == null ? ManagementFactory.getRuntimeMXBean().getName() : identity);
    }
  }

  private static final WorkflowClientInterceptor[] EMPTY_INTERCEPTOR_ARRAY =
      new WorkflowClientInterceptor[0];

  private final String domain;

  private final DataConverter dataConverter;

  private final WorkflowClientInterceptor[] interceptors;

  private final Scope metricsScope;

  private final String identity;

  private WorkflowClientOptions(
      String domain,
      DataConverter dataConverter,
      WorkflowClientInterceptor[] interceptors,
      Scope metricsScope,
      String identity) {
    this.domain = domain;
    this.dataConverter = dataConverter;
    this.interceptors = interceptors;
    this.metricsScope = metricsScope;
    this.identity = identity;
  }

  public String getDomain() {
    return domain;
  }

  public DataConverter getDataConverter() {
    return dataConverter;
  }

  public WorkflowClientInterceptor[] getInterceptors() {
    return interceptors;
  }

  public Scope getMetricsScope() {
    return metricsScope;
  }

  public String getIdentity() {
    return identity;
  }
}
