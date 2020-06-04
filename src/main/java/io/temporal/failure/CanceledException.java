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
import io.temporal.proto.failure.CanceledFailureInfo;
import io.temporal.proto.failure.Failure;
import java.lang.reflect.Type;
import java.util.Optional;

public final class CanceledException extends RemoteException {
  private final Optional<Payloads> details;
  private final DataConverter dataConverter;

  CanceledException(Failure failure, DataConverter dataConverter, Exception cause) {
    super(failure, cause);
    if (!failure.hasCanceledFailureInfo()) {
      throw new IllegalArgumentException(
          "Canceled failure expected: " + failure.getCanceledFailureInfo());
    }
    CanceledFailureInfo info = failure.getCanceledFailureInfo();
    this.details = info.hasDetails() ? Optional.of(info.getDetails()) : Optional.empty();
    this.dataConverter = dataConverter;
  }

  Optional<Payloads> getDetails() {
    return details;
  }

  public <V> V getDetails(Class<V> detailsClass) {
    return getDetails(detailsClass, detailsClass);
  }

  public <V> V getDetails(Class<V> detailsClass, Type detailsType) {
    return dataConverter.fromData(details, detailsClass, detailsType);
  }
}
