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

import static io.temporal.failure.FailureConverter.JAVA_SDK;

import com.google.common.base.Strings;
import io.temporal.common.converter.DataConverter;
import io.temporal.internal.common.DataConverterUtils;
import io.temporal.proto.failure.Failure;

public abstract class RemoteException extends TemporalException {

  private final String failureSource;
  private final String failureStackTrace;

  protected RemoteException(Failure failure, Exception cause) {
    super(failure.getMessage(), cause);
    this.failureSource = failure.getSource();
    this.failureStackTrace = failure.getStackTrace();
    if (JAVA_SDK.equals(this.failureSource) && !Strings.isNullOrEmpty(this.failureStackTrace)) {
      StackTraceElement[] stackTrace = DataConverterUtils.parseStackTrace(this.failureStackTrace);
      if (stackTrace != null) {
        setStackTrace(stackTrace);
      }
    }
  }

  protected RemoteException(String message, Failure cause, DataConverter dataConverter) {
    super(message, FailureConverter.failureToException(cause, dataConverter));
    this.failureSource = JAVA_SDK;
    this.failureStackTrace = "";
  }

  protected RemoteException(Failure failure) {
    this(failure, null);
  }

  public String getFailureSource() {
    return failureSource;
  }

  public String getFailureStackTrace() {
    return failureStackTrace;
  }

  @Override
  public String toString() {
    String s = getClass().getName();
    if (failureSource.equals(JAVA_SDK) || Strings.isNullOrEmpty(failureStackTrace)) {
      return super.toString();
    }
    return s + ": " + getMessage() + "\n" + failureStackTrace;
  }
}
