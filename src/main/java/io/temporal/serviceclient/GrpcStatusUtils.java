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

package io.temporal.serviceclient;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import io.grpc.Metadata;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.ProtoUtils;
import io.temporal.CancellationAlreadyRequested;
import io.temporal.ClientVersionNotSupported;
import io.temporal.CurrentBranchChanged;
import io.temporal.DomainAlreadyExists;
import io.temporal.DomainNotActive;
import io.temporal.EventAlreadyStarted;
import io.temporal.QueryFailed;
import io.temporal.RetryTask;
import io.temporal.RetryTaskV2;
import io.temporal.ShardOwnershipLost;
import io.temporal.WorkflowExecutionAlreadyStarted;

@SuppressWarnings(
    "unchecked") // Raw use of parametrized class to enable more efficient and briefer code.
public class GrpcStatusUtils {
  static final ImmutableMap<GrpcFailure, Metadata.Key> KEY_MAP;

  static {
    KEY_MAP =
        ImmutableMap.<GrpcFailure, Metadata.Key>builder()
            .put(
                GrpcFailure.CANCELLATION_ALREADY_REQUESTED,
                ProtoUtils.keyForProto(CancellationAlreadyRequested.getDefaultInstance()))
            .put(
                GrpcFailure.CLIENT_VERSION_NOT_SUPPORTED,
                ProtoUtils.keyForProto(ClientVersionNotSupported.getDefaultInstance()))
            .put(
                GrpcFailure.CURRENT_BRANCH_CHANGED,
                ProtoUtils.keyForProto(CurrentBranchChanged.getDefaultInstance()))
            .put(
                GrpcFailure.DOMAIN_ALREADY_EXISTS_FAILURE,
                ProtoUtils.keyForProto(DomainAlreadyExists.getDefaultInstance()))
            .put(
                GrpcFailure.DOMAIN_NOT_ACTIVE,
                ProtoUtils.keyForProto(DomainNotActive.getDefaultInstance()))
            .put(
                GrpcFailure.EVENT_ALREADY_STARTED,
                ProtoUtils.keyForProto(EventAlreadyStarted.getDefaultInstance()))
            .put(GrpcFailure.QUERY_FAILED, ProtoUtils.keyForProto(QueryFailed.getDefaultInstance()))
            .put(GrpcFailure.RETRY_TASK, ProtoUtils.keyForProto(RetryTask.getDefaultInstance()))
            .put(
                GrpcFailure.RETRY_TASK_V2, ProtoUtils.keyForProto(RetryTaskV2.getDefaultInstance()))
            .put(
                GrpcFailure.SHARD_OWNERSHIP_LOST,
                ProtoUtils.keyForProto(ShardOwnershipLost.getDefaultInstance()))
            .put(
                GrpcFailure.WORKFLOW_EXECUTION_ALREADY_STARTED_FAILURE,
                ProtoUtils.keyForProto(WorkflowExecutionAlreadyStarted.getDefaultInstance()))
            .build();
  }

  /**
   * Determines if a StatusRuntimeException contains a failure message of a given name.
   *
   * @return true if the given failure is found, false otherwise
   */
  public static boolean hasFailure(StatusRuntimeException exception, Enum failureName) {
    Preconditions.checkNotNull(exception, "Exception cannot be null");
    Preconditions.checkArgument(
        KEY_MAP.containsKey(failureName), "Unknown failure name: %s", failureName);

    Metadata metadata = exception.getTrailers();
    return metadata.containsKey(KEY_MAP.get(failureName));
  }

  /** @return a failure of a given type from the StatusRuntimeException object */
  public static Object getFailure(StatusRuntimeException exception, Enum failureName) {
    Preconditions.checkNotNull(exception, "Exception cannot be null");
    Preconditions.checkArgument(
        KEY_MAP.containsKey(failureName), "Unknown failure name: %s", failureName);

    Metadata metadata = exception.getTrailers();
    return metadata.get(KEY_MAP.get(failureName));
  }
  /** Set a failure of a given type for the StatusRuntimeException object. */
  public static <T> void setFailure(
      StatusRuntimeException exception, GrpcFailure failureName, T value) {
    Preconditions.checkNotNull(exception, "Exception cannot be null");
    Preconditions.checkArgument(
        KEY_MAP.containsKey(failureName), "Unknown failure name: %s", failureName);

    Metadata metadata = exception.getTrailers();
    metadata.<T>put(KEY_MAP.get(failureName), value);
  }
}
