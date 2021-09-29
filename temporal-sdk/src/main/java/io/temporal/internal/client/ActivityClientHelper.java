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

package io.temporal.internal.client;

import static io.temporal.serviceclient.MetricsTag.METRICS_TAGS_CALL_OPTIONS_KEY;

import com.google.protobuf.ByteString;
import com.uber.m3.tally.Scope;
import io.temporal.activity.ManualActivityCompletionClient;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatByIdRequest;
import io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatByIdResponse;
import io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatRequest;
import io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatResponse;
import io.temporal.common.converter.DataConverter;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.Optional;

/**
 * Contains methods that could but didn't become a part of the main {@link
 * ManualActivityCompletionClient}, because they are not intended to be called by our users
 * directly.
 */
public class ActivityClientHelper {
  public static RecordActivityTaskHeartbeatResponse sendHeartbeatRequest(
      WorkflowServiceStubs service,
      String namespace,
      String identity,
      byte[] taskToken,
      DataConverter dataConverter,
      Scope metricsScope,
      Object details) {
    RecordActivityTaskHeartbeatRequest.Builder request =
        RecordActivityTaskHeartbeatRequest.newBuilder()
            .setTaskToken(ByteString.copyFrom(taskToken))
            .setNamespace(namespace)
            .setIdentity(identity);
    Optional<Payloads> payloads = dataConverter.toPayloads(details);
    payloads.ifPresent(request::setDetails);
    return service
        .blockingStub()
        .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
        .recordActivityTaskHeartbeat(request.build());
  }

  public static RecordActivityTaskHeartbeatByIdResponse recordActivityTaskHeartbeatById(
      WorkflowServiceStubs service,
      String namespace,
      String identity,
      WorkflowExecution execution,
      String activityId,
      DataConverter dataConverter,
      Scope metricsScope,
      Object details) {
    if (activityId == null) {
      throw new IllegalArgumentException("Either activity id or task token are required");
    }
    RecordActivityTaskHeartbeatByIdRequest.Builder request =
        RecordActivityTaskHeartbeatByIdRequest.newBuilder()
            .setRunId(execution.getRunId())
            .setWorkflowId(execution.getWorkflowId())
            .setActivityId(activityId)
            .setNamespace(namespace)
            .setIdentity(identity);
    Optional<Payloads> payloads = dataConverter.toPayloads(details);
    payloads.ifPresent(request::setDetails);
    return service
        .blockingStub()
        .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
        .recordActivityTaskHeartbeatById(request.build());
  }
}
