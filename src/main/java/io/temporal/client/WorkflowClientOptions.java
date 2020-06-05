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

import io.temporal.common.context.ContextPropagator;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.proto.query.QueryRejectCondition;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Options for WorkflowClient configuration. */
public final class WorkflowClientOptions {

  private static final WorkflowClientOptions DEFAULT_INSTANCE;
  private static final String DEFAULT_NAMESPACE = "default";

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

    private String namespace;
    private DataConverter dataConverter;
    private WorkflowClientInterceptor[] interceptors;
    private String identity;
    private List<ContextPropagator> contextPropagators;
    private QueryRejectCondition queryRejectCondition;

    private Builder() {}

    private Builder(WorkflowClientOptions options) {
      if (options == null) {
        return;
      }
      namespace = options.namespace;
      dataConverter = options.dataConverter;
      interceptors = options.interceptors;
      identity = options.identity;
      contextPropagators = options.contextPropagators;
      queryRejectCondition = options.queryRejectCondition;
    }

    public Builder setNamespace(String namespace) {
      this.namespace = namespace;
      return this;
    }

    /**
     * Overrides a data converter implementation used serialize workflow and activity arguments and
     * results.
     *
     * <p>Default is {@link DefaultDataConverter} data converter.
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
     * Override human readable identity of the worker. Identity is used to identify a worker and is
     * recorded in the workflow history events. For example when a worker gets an activity task the
     * correspondent ActivityTaskStarted event contains the worker identity as a field. Default is
     * "unknown-mac" string on Mac or whatever <code>(ManagementFactory.getRuntimeMXBean().getName()
     * </code> returns on any other platform. The reason for treating Mac differently is a very slow
     * local host name resolution in a default configuration.
     */
    public Builder setIdentity(String identity) {
      this.identity = identity;
      return this;
    }

    public Builder setContextPropagators(List<ContextPropagator> contextPropagators) {
      this.contextPropagators = contextPropagators;
      return this;
    }

    /**
     * Should a query be rejected by closed and failed workflows.
     *
     * <p>Default is {@link QueryRejectCondition#None} which means that closed and failed workflows
     * are still queryable.
     */
    public Builder setQueryRejectCondition(QueryRejectCondition queryRejectCondition) {
      this.queryRejectCondition = queryRejectCondition;
      return this;
    }

    public WorkflowClientOptions build() {
      return new WorkflowClientOptions(
          namespace,
          dataConverter,
          interceptors,
          identity,
          contextPropagators,
          queryRejectCondition);
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
          namespace == null ? DEFAULT_NAMESPACE : namespace,
          dataConverter == null ? DefaultDataConverter.getInstance() : dataConverter,
          interceptors == null ? EMPTY_INTERCEPTOR_ARRAY : interceptors,
          name,
          contextPropagators == null ? EMPTY_CONTEXT_PROPAGATORS : contextPropagators,
          queryRejectCondition == null ? QueryRejectCondition.None : queryRejectCondition);
    }
  }

  private static final WorkflowClientInterceptor[] EMPTY_INTERCEPTOR_ARRAY =
      new WorkflowClientInterceptor[0];

  private static final List<ContextPropagator> EMPTY_CONTEXT_PROPAGATORS = Arrays.asList();

  private final String namespace;

  private final DataConverter dataConverter;

  private final WorkflowClientInterceptor[] interceptors;

  private final String identity;

  private final List<ContextPropagator> contextPropagators;

  private final QueryRejectCondition queryRejectCondition;

  private WorkflowClientOptions(
      String namespace,
      DataConverter dataConverter,
      WorkflowClientInterceptor[] interceptors,
      String identity,
      List<ContextPropagator> contextPropagators,
      QueryRejectCondition queryRejectCondition) {
    this.namespace = namespace;
    this.dataConverter = dataConverter;
    this.interceptors = interceptors;
    this.identity = identity;
    this.contextPropagators = contextPropagators;
    this.queryRejectCondition = queryRejectCondition;
  }

  public String getNamespace() {
    return namespace;
  }

  public DataConverter getDataConverter() {
    return dataConverter;
  }

  public WorkflowClientInterceptor[] getInterceptors() {
    return interceptors;
  }

  public String getIdentity() {
    return identity;
  }

  public List<ContextPropagator> getContextPropagators() {
    return contextPropagators;
  }

  public QueryRejectCondition getQueryRejectCondition() {
    return queryRejectCondition;
  }

  @Override
  public String toString() {
    return "WorkflowClientOptions{"
        + "namespace='"
        + namespace
        + '\''
        + ", dataConverter="
        + dataConverter
        + ", interceptors="
        + Arrays.toString(interceptors)
        + ", identity='"
        + identity
        + '\''
        + ", contextPropagators="
        + contextPropagators
        + ", queryRejectCondition="
        + queryRejectCondition
        + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    WorkflowClientOptions that = (WorkflowClientOptions) o;
    return com.google.common.base.Objects.equal(namespace, that.namespace)
        && com.google.common.base.Objects.equal(dataConverter, that.dataConverter)
        && Arrays.equals(interceptors, that.interceptors)
        && com.google.common.base.Objects.equal(identity, that.identity)
        && com.google.common.base.Objects.equal(contextPropagators, that.contextPropagators)
        && queryRejectCondition == that.queryRejectCondition;
  }

  @Override
  public int hashCode() {
    return com.google.common.base.Objects.hashCode(
        namespace,
        dataConverter,
        Arrays.hashCode(interceptors),
        identity,
        contextPropagators,
        queryRejectCondition);
  }
}
