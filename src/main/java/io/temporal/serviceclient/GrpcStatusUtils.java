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
import com.google.protobuf.GeneratedMessageV3;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.ProtoUtils;
import io.temporal.proto.failure.CancellationAlreadyRequested;
import io.temporal.proto.failure.ClientVersionNotSupported;
import io.temporal.proto.failure.CurrentBranchChanged;
import io.temporal.proto.failure.DomainAlreadyExists;
import io.temporal.proto.failure.DomainNotActive;
import io.temporal.proto.failure.EventAlreadyStarted;
import io.temporal.proto.failure.QueryFailed;
import io.temporal.proto.failure.RetryTask;
import io.temporal.proto.failure.RetryTaskV2;
import io.temporal.proto.failure.ShardOwnershipLost;
import io.temporal.proto.failure.WorkflowExecutionAlreadyStarted;

public class GrpcStatusUtils {

  private static final ImmutableMap<Class<? extends GeneratedMessageV3>, Metadata.Key> KEY_MAP;

  static {
    KEY_MAP =
        ImmutableMap.<Class<? extends GeneratedMessageV3>, Metadata.Key>builder()
            .put(
                CancellationAlreadyRequested.class,
                ProtoUtils.keyForProto(CancellationAlreadyRequested.getDefaultInstance()))
            .put(
                ClientVersionNotSupported.class,
                ProtoUtils.keyForProto(ClientVersionNotSupported.getDefaultInstance()))
            .put(
                CurrentBranchChanged.class,
                ProtoUtils.keyForProto(CurrentBranchChanged.getDefaultInstance()))
            .put(
                DomainAlreadyExists.class,
                ProtoUtils.keyForProto(DomainAlreadyExists.getDefaultInstance()))
            .put(
                DomainNotActive.class, ProtoUtils.keyForProto(DomainNotActive.getDefaultInstance()))
            .put(
                EventAlreadyStarted.class,
                ProtoUtils.keyForProto(EventAlreadyStarted.getDefaultInstance()))
            .put(QueryFailed.class, ProtoUtils.keyForProto(QueryFailed.getDefaultInstance()))
            .put(RetryTask.class, ProtoUtils.keyForProto(RetryTask.getDefaultInstance()))
            .put(RetryTaskV2.class, ProtoUtils.keyForProto(RetryTaskV2.getDefaultInstance()))
            .put(
                ShardOwnershipLost.class,
                ProtoUtils.keyForProto(ShardOwnershipLost.getDefaultInstance()))
            .put(
                WorkflowExecutionAlreadyStarted.class,
                ProtoUtils.keyForProto(WorkflowExecutionAlreadyStarted.getDefaultInstance()))
            .build();
  }

  /**
   * Determines if a StatusRuntimeException contains a failure message of a given type.
   *
   * @return true if the given failure is found, false otherwise
   */
  public static boolean hasFailure(
      StatusRuntimeException exception, Class<? extends GeneratedMessageV3> failureType) {
    Preconditions.checkNotNull(exception, "Exception cannot be null");
    Metadata metadata = exception.getTrailers();
    if (metadata == null) {
      return false;
    }
    Metadata.Key key = KEY_MAP.get(failureType);
    Preconditions.checkNotNull(key, "Unknown failure type: %s", failureType.getName());
    return metadata.containsKey(key);
  }

  /** @return a failure of a given type from the StatusRuntimeException object */
  public static <T extends GeneratedMessageV3> T getFailure(
      StatusRuntimeException exception, Class<T> failureType) {
    Preconditions.checkNotNull(exception, "Exception cannot be null");
    Metadata metadata = exception.getTrailers();
    if (metadata == null) {
      return null;
    }
    Metadata.Key key = KEY_MAP.get(failureType);
    Preconditions.checkNotNull(key, "Unknown failure type: %s", failureType.getName());
    return (T) metadata.get(key);
  }

  /** Create StatusRuntimeException with given details. */
  public static <T extends GeneratedMessageV3> StatusRuntimeException newException(
      Status status, T details) {
    Preconditions.checkNotNull(status, "Exception cannot be null");
    StatusRuntimeException result = status.asRuntimeException(new Metadata());
    Metadata metadata = result.getTrailers();
    Metadata.Key key = KEY_MAP.get(details.getClass());
    Preconditions.checkNotNull(key, "Unknown failure type: %s", details.getClass().getName());
    metadata.<T>put(key, details);
    return result;
  }
}
