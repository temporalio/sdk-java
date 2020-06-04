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

package io.temporal.failure;

import io.temporal.common.converter.DataConverter;
import io.temporal.proto.common.Payloads;
import io.temporal.proto.failure.Failure;
import io.temporal.proto.failure.ResetWorkflowFailureInfo;
import java.lang.reflect.Type;
import java.util.Optional;

public final class ResetWorkflowException extends RemoteException {
  private final Optional<Payloads> lastHeartbeatDetails;
  private final DataConverter dataConverter;

  ResetWorkflowException(Failure failure, DataConverter dataConverter, Exception cause) {
    super(failure);
    if (!failure.hasResetWorkflowFailureInfo()) {
      throw new IllegalArgumentException(
          "Timeout failure expected: " + failure.getFailureInfoCase());
    }
    ResetWorkflowFailureInfo info = failure.getResetWorkflowFailureInfo();
    this.lastHeartbeatDetails =
        info.hasLastHeartbeatDetails()
            ? Optional.of(info.getLastHeartbeatDetails())
            : Optional.empty();
    this.dataConverter = dataConverter;
  }

  Optional<Payloads> getLastHeartbeatDetails() {
    return lastHeartbeatDetails;
  }

  public <V> V getLastHeartbeatDetails(Class<V> detailsClass) {
    return getLastHeartbeatDetails(detailsClass, detailsClass);
  }

  public <V> V getLastHeartbeatDetails(Class<V> detailsClass, Type detailsType) {
    return dataConverter.fromData(lastHeartbeatDetails, detailsClass, detailsType);
  }
}
