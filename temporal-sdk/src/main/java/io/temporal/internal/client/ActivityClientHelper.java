/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.temporal.internal.client;

import static io.temporal.serviceclient.MetricsTag.METRICS_TAGS_CALL_OPTIONS_KEY;

import com.google.common.base.Preconditions;
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
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Contains methods that could but didn't become a part of the main {@link
 * ManualActivityCompletionClient}, because they are not intended to be called by our users
 * directly.
 */
public final class ActivityClientHelper {
  private ActivityClientHelper() {}

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
      @Nonnull String activityId,
      DataConverter dataConverter,
      Scope metricsScope,
      @Nullable Object details) {
    Preconditions.checkNotNull(activityId, "Either activity id or task token are required");
    RecordActivityTaskHeartbeatByIdRequest.Builder request =
        RecordActivityTaskHeartbeatByIdRequest.newBuilder()
            .setRunId(execution.getRunId())
            .setWorkflowId(execution.getWorkflowId())
            .setActivityId(activityId)
            .setNamespace(namespace)
            .setIdentity(identity);
    dataConverter.toPayloads(details).ifPresent(request::setDetails);
    return service
        .blockingStub()
        .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
        .recordActivityTaskHeartbeatById(request.build());
  }
}
