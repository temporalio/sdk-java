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
import io.temporal.client.ActivityCanceledException;
import io.temporal.common.converter.DataConverter;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.Optional;

/**
 * Contains methods that could but didn't become a part of the main {@link
 * ManualActivityCompletionClient}, because they are not intended to be called by our users
 * directly.
 */
public class ActivityClientHelper {
  public static void sendHeartbeatRequest(
      DataConverter dataConverter,
      String identity,
      Scope metricsScope,
      String namespace,
      WorkflowServiceStubs service,
      byte[] taskToken,
      Object details) {
    RecordActivityTaskHeartbeatRequest.Builder request =
        RecordActivityTaskHeartbeatRequest.newBuilder()
            .setTaskToken(ByteString.copyFrom(taskToken))
            .setNamespace(namespace)
            .setIdentity(identity);
    Optional<Payloads> payloads = dataConverter.toPayloads(details);
    payloads.ifPresent(request::setDetails);
    RecordActivityTaskHeartbeatResponse status;
    status =
        service
            .blockingStub()
            .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
            .recordActivityTaskHeartbeat(request.build());
    if (status.getCancelRequested()) {
      throw new ActivityCanceledException();
    }
  }

  public static void recordActivityTaskHeartbeatById(
      String activityId,
      DataConverter dataConverter,
      WorkflowExecution execution,
      Scope metricsScope,
      String namespace,
      WorkflowServiceStubs service,
      Object details) {
    if (activityId == null) {
      throw new IllegalArgumentException("Either activity id or task token are required");
    }
    RecordActivityTaskHeartbeatByIdRequest.Builder request =
        RecordActivityTaskHeartbeatByIdRequest.newBuilder()
            .setWorkflowId(execution.getWorkflowId())
            .setNamespace(namespace)
            .setRunId(execution.getRunId())
            .setActivityId(activityId);
    Optional<Payloads> payloads = dataConverter.toPayloads(details);
    payloads.ifPresent(request::setDetails);
    RecordActivityTaskHeartbeatByIdResponse status;
    status =
        service
            .blockingStub()
            .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
            .recordActivityTaskHeartbeatById(request.build());
    if (status.getCancelRequested()) {
      throw new ActivityCanceledException();
    }
  }
}
