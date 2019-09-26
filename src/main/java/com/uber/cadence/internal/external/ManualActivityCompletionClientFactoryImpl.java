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

package com.uber.cadence.internal.external;

import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.converter.DataConverter;
import com.uber.cadence.internal.metrics.MetricsTag;
import com.uber.cadence.serviceclient.IWorkflowService;
import com.uber.m3.tally.Scope;
import com.uber.m3.util.ImmutableMap;
import java.util.Map;

public class ManualActivityCompletionClientFactoryImpl
    extends ManualActivityCompletionClientFactory {

  private final IWorkflowService service;
  private final DataConverter dataConverter;
  private final String domain;
  private final Scope metricsScope;

  public ManualActivityCompletionClientFactoryImpl(
      IWorkflowService service, String domain, DataConverter dataConverter, Scope metricsScope) {
    this.service = service;
    this.domain = domain;
    this.dataConverter = dataConverter;

    Map<String, String> tags =
        new ImmutableMap.Builder<String, String>(1).put(MetricsTag.DOMAIN, domain).build();
    this.metricsScope = metricsScope.tagged(tags);
  }

  public IWorkflowService getService() {
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
    return new ManualActivityCompletionClientImpl(service, taskToken, dataConverter, metricsScope);
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
        service, domain, execution, activityId, dataConverter, metricsScope);
  }
}
