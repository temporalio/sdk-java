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
import io.temporal.activity.ManualActivityCompletionClient;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.common.converter.DataConverter;
import io.temporal.serviceclient.WorkflowServiceStubs;

public interface ManualActivityCompletionClientFactory {

  /**
   * Create a {@link ManualActivityCompletionClientFactory} that emits simple {@link
   * ManualActivityCompletionClientImpl} implementations
   */
  static ManualActivityCompletionClientFactory newFactory(
      WorkflowServiceStubs service,
      String namespace,
      String identity,
      DataConverter dataConverter) {
    return new ManualActivityCompletionClientFactoryImpl(
        service, namespace, identity, dataConverter);
  }

  ManualActivityCompletionClient getClient(byte[] taskToken, Scope metricsScope);

  ManualActivityCompletionClient getClient(
      WorkflowExecution execution, String activityId, Scope metricsScope);
}
