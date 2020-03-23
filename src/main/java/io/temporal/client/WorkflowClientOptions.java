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

package io.temporal.client;

import com.uber.m3.tally.Scope;
import io.temporal.common.context.ContextPropagator;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.JsonDataConverter;
import io.temporal.internal.metrics.NoopScope;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Options for WorkflowClient configuration. */
public final class WorkflowClientOptions {

  private static final WorkflowClientOptions DEFAULT_INSTANCE;
  private static final String DEFAULT_DOMAIN = "default";

  static {
    DEFAULT_INSTANCE = newBuilder().build();
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(WorkflowClientOptions options) {
    return new Builder(options);
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
    private List<ContextPropagator> contextPropagators;

    private Builder() {}

    private Builder(WorkflowClientOptions options) {
      if (options == null) {
        return;
      }
      domain = options.domain;
      dataConverter = options.dataConverter;
      interceptors = options.interceptors;
      metricsScope = options.metricsScope;
      identity = options.identity;
      contextPropagators = options.contextPropagators;
    }

    public Builder setDomain(String domain) {
      this.domain = domain;
      return this;
    }

    /**
     * Overrides a data converter implementation used serialize workflow and activity arguments and
     * results.
     *
     * <p>Default is {@link io.temporal.common.converter.JsonDataConverter} data converter.
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
     * "unknown-mac" string on Mac or whatever <code>(ManagementFactory.getRuntimeMXBean().getName()
     * </code> returns on any other platform. The reason for treating Mac differently is a very slow
     * local host name resolution in a default configuration.
     *
     * @return
     */
    public Builder setIdentity(String identity) {
      this.identity = identity;
      return this;
    }

    public Builder setContextPropagators(List<ContextPropagator> contextPropagators) {
      this.contextPropagators = contextPropagators;
      return this;
    }

    public WorkflowClientOptions build() {
      return new WorkflowClientOptions(
          domain, dataConverter, interceptors, metricsScope, identity, contextPropagators);
    }

    public WorkflowClientOptions validateAndBuildWithDefaults() {
      String name;
      if (identity == null) {
        String osName = System.getProperty("os.name");
        // On mac by default getLocalHost (which getRuntimeMXBean().getName() calls) takes long
        // time. So we don't set identity on mac by default to avoid the bad user experience.
        // https://stackoverflow.com/questions/33289695/inetaddress-getlocalhost-slow-to-run-30-seconds
        if (osName.toLowerCase().contains("mac")) {
          name = "unknown-mac";
        } else {
          name = ManagementFactory.getRuntimeMXBean().getName();
        }
      } else {
        name = identity;
      }
      return new WorkflowClientOptions(
          domain == null ? DEFAULT_DOMAIN : domain,
          dataConverter == null ? JsonDataConverter.getInstance() : dataConverter,
          interceptors == null ? EMPTY_INTERCEPTOR_ARRAY : interceptors,
          metricsScope == null ? NoopScope.getInstance() : metricsScope,
          name,
          contextPropagators == null ? EMPTY_CONTEXT_PROPAGATORS : contextPropagators);
    }
  }

  private static final WorkflowClientInterceptor[] EMPTY_INTERCEPTOR_ARRAY =
      new WorkflowClientInterceptor[0];

  private static final List<ContextPropagator> EMPTY_CONTEXT_PROPAGATORS = Arrays.asList();

  private final String domain;

  private final DataConverter dataConverter;

  private final WorkflowClientInterceptor[] interceptors;

  private final Scope metricsScope;

  private final String identity;

  private final List<ContextPropagator> contextPropagators;

  private WorkflowClientOptions(
      String domain,
      DataConverter dataConverter,
      WorkflowClientInterceptor[] interceptors,
      Scope metricsScope,
      String identity,
      List<ContextPropagator> contextPropagators) {
    this.domain = domain;
    this.dataConverter = dataConverter;
    this.interceptors = interceptors;
    this.metricsScope = metricsScope;
    this.identity = identity;
    this.contextPropagators = contextPropagators;
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

  public List<ContextPropagator> getContextPropagators() {
    return contextPropagators;
  }

  @Override
  public String toString() {
    return "WorkflowClientOptions{"
        + "domain='"
        + domain
        + '\''
        + ", dataConverter="
        + dataConverter
        + ", interceptors="
        + Arrays.toString(interceptors)
        + ", metricsScope="
        + metricsScope
        + ", identity='"
        + identity
        + '\''
        + ", contextPropagators="
        + contextPropagators
        + '}';
  }
}
