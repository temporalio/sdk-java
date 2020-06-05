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
import io.temporal.proto.failure.ApplicationFailureInfo;
import io.temporal.proto.failure.Failure;
import java.lang.reflect.Type;
import java.util.Optional;

public final class ApplicationException extends RemoteException {
  private final String type;
  private final Optional<Payloads> details;
  private final DataConverter dataConverter;
  private final boolean nonRetryable;

  ApplicationException(Failure failure, DataConverter dataConverter, Exception cause) {
    super(toString(failure), failure, cause);
    ApplicationFailureInfo info = failure.getApplicationFailureInfo();
    this.type = info.getType();
    this.details = info.hasDetails() ? Optional.of(info.getDetails()) : Optional.empty();
    this.dataConverter = dataConverter;
    this.nonRetryable = info.getNonRetryable();
  }

  public String getType() {
    return type;
  }

  Optional<Payloads> getDetails() {
    return details;
  }

  public boolean isNonRetryable() {
    return nonRetryable;
  }

  public <V> V getDetails(Class<V> detailsClass) {
    return getDetails(detailsClass, detailsClass);
  }

  public <V> V getDetails(Class<V> detailsClass, Type detailsType) {
    return dataConverter.fromData(details, detailsClass, detailsType);
  }

  private static String toString(Failure failure) {
    if (!failure.hasApplicationFailureInfo()) {
      throw new IllegalArgumentException(
          "Application failure expected: " + failure.getFailureInfoCase());
    }
    ApplicationFailureInfo info = failure.getApplicationFailureInfo();

    StringBuilder result = new StringBuilder();
    result.append("type=\"");
    result.append(info.getType());
    result.append('"');
    if (info.hasDetails()) {
      result.append(", details=\"");
      result.append(info.getDetails());
      result.append('"');
    }
    result.append('}');
    return result.toString();
  }

  @Override
  public String toString() {
    StringBuilder result = new StringBuilder();
    result.append(this.getClass().getName());
    result.append(" ");
    result.append(toString(failure));
    result.append('}');
    return result.toString();
  }
}
