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

package io.temporal.serviceclient;

import com.google.common.base.Preconditions;
import com.google.protobuf.*;
import com.google.rpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StatusUtils {

  private static final Logger log = LoggerFactory.getLogger(StatusUtils.class);

  /**
   * Determines if a StatusRuntimeException contains a failure message of a given type.
   *
   * @return true if the given failure is found, false otherwise
   */
  public static boolean hasFailure(
      StatusRuntimeException exception, Class<? extends GeneratedMessageV3> failureType) {
    Preconditions.checkNotNull(exception, "exception cannot be null");
    com.google.rpc.Status status = StatusProto.fromThrowable(exception);
    if (status.getDetailsCount() == 0) {
      return false;
    }
    Any details = status.getDetails(0);
    return details.is(failureType);
  }

  /**
   * @return a failure of a given type from the StatusRuntimeException object
   */
  public static <T extends GeneratedMessageV3> T getFailure(
      StatusRuntimeException exception, Class<T> failureType) {
    Preconditions.checkNotNull(exception, "exception cannot be null");
    com.google.rpc.Status status = StatusProto.fromThrowable(exception);
    if (status.getDetailsCount() == 0) {
      return null;
    }
    Any details = status.getDetails(0);
    try {
      if (details.is(failureType)) {
        return details.unpack(failureType);
      }
    } catch (InvalidProtocolBufferException e) {
      throw new IllegalArgumentException(
          "failure getting grcp failure of " + failureType + " from " + details, e);
    }
    return null;
  }

  /** Create StatusRuntimeException with given details. */
  public static <T extends GeneratedMessageV3> StatusRuntimeException newException(
      io.grpc.Status status, T details, Descriptors.Descriptor detailsDescriptor) {
    Preconditions.checkNotNull(status, "status cannot be null");
    Status protoStatus =
        Status.newBuilder()
            .setCode(status.getCode().value())
            .setMessage(status.getDescription())
            .addDetails(packAny(details, detailsDescriptor))
            .build();
    return StatusProto.toStatusRuntimeException(protoStatus);
  }

  /**
   * This method does exactly what {@link Any#pack(Message)} does. But it doesn't go into reflection
   * to fetch the {@code descriptor}, which allows us to avoid a bunch of Graal reflection configs.
   */
  public static <T extends GeneratedMessageV3> Any packAny(
      T details, Descriptors.Descriptor descriptor) {
    return Any.newBuilder()
        .setTypeUrl("type.googleapis.com/" + descriptor.getFullName())
        .setValue(details.toByteString())
        .build();
  }
}
