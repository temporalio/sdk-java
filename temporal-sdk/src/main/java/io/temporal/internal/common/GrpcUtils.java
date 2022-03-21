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

package io.temporal.internal.common;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;

public class GrpcUtils {
  /** @return true if {@code ex} is a gRPC exception about a channel shutdown */
  public static boolean isChannelShutdownException(StatusRuntimeException ex) {
    String description = ex.getStatus().getDescription();
    return (Status.Code.UNAVAILABLE.equals(ex.getStatus().getCode())
        && description != null
        && (description.startsWith("Channel shutdown")
            || description.startsWith("Subchannel shutdown")));
  }
}
