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

package com.uber.cadence.client;

import com.uber.cadence.converter.DataConverter;
import com.uber.cadence.converter.JsonDataConverter;
import com.uber.cadence.internal.metrics.NoopScope;
import com.uber.m3.tally.Scope;
import java.util.Objects;

/** Options for WorkflowClient configuration. */
public final class WorkflowClientOptions {

  public static final class Builder {

    private DataConverter dataConverter = JsonDataConverter.getInstance();

    private WorkflowClientInterceptor[] interceptors = EMPTY_INTERCEPTOR_ARRAY;

    private Scope metricsScope;

    /**
     * Used to override default (JSON) data converter implementation.
     *
     * @param dataConverter data converter to serialize and deserialize arguments and return values.
     *     Not null.
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

    public WorkflowClientOptions build() {
      if (metricsScope == null) {
        metricsScope = NoopScope.getInstance();
      }
      return new WorkflowClientOptions(dataConverter, interceptors, metricsScope);
    }
  }

  private static final WorkflowClientInterceptor[] EMPTY_INTERCEPTOR_ARRAY =
      new WorkflowClientInterceptor[0];
  private final DataConverter dataConverter;

  private final WorkflowClientInterceptor[] interceptors;

  private final Scope metricsScope;

  private WorkflowClientOptions(
      DataConverter dataConverter, WorkflowClientInterceptor[] interceptors, Scope metricsScope) {
    this.dataConverter = dataConverter;
    this.interceptors = interceptors;
    this.metricsScope = metricsScope;
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
}
