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

package io.temporal.internal.client.external;

import com.uber.m3.tally.Scope;
import com.uber.m3.util.ImmutableMap;
import io.temporal.activity.ManualActivityCompletionClient;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.common.converter.DataConverter;
import io.temporal.serviceclient.MetricsTag;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.Map;
import java.util.Objects;

public class ManualActivityCompletionClientFactoryImpl
    implements ManualActivityCompletionClientFactory {

  private final WorkflowServiceStubs service;
  private final DataConverter dataConverter;
  private final String namespace;
  private final String identity;
  private final Scope metricsScope;

  public ManualActivityCompletionClientFactoryImpl(
      WorkflowServiceStubs service,
      String namespace,
      String identity,
      DataConverter dataConverter,
      Scope metricsScope) {
    this.service = Objects.requireNonNull(service);
    this.namespace = Objects.requireNonNull(namespace);
    this.identity = Objects.requireNonNull(identity);
    this.dataConverter = Objects.requireNonNull(dataConverter);

    Map<String, String> tags =
        new ImmutableMap.Builder<String, String>(1).put(MetricsTag.NAMESPACE, namespace).build();
    this.metricsScope = metricsScope.tagged(tags);
  }

  public WorkflowServiceStubs getService() {
    return service;
  }

  public DataConverter getDataConverter() {
    return dataConverter;
  }

  @Override
  public ManualActivityCompletionClient getClient(byte[] taskToken) {
    if (service == null) {
      throw new IllegalStateException("required property service is null");
    }
    if (dataConverter == null) {
      throw new IllegalStateException("required property dataConverter is null");
    }
    if (taskToken == null || taskToken.length == 0) {
      throw new IllegalArgumentException("null or empty task token");
    }
    return new ManualActivityCompletionClientImpl(
        service, namespace, identity, taskToken, dataConverter, metricsScope);
  }

  @Override
  public ManualActivityCompletionClient getClient(WorkflowExecution execution, String activityId) {
    if (execution == null) {
      throw new IllegalArgumentException("null execution");
    }
    if (activityId == null) {
      throw new IllegalArgumentException("null activityId");
    }
    return new ManualActivityCompletionClientImpl(
        service, namespace, identity, execution, activityId, dataConverter, metricsScope);
  }
}
