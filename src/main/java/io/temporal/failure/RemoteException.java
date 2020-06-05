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
import io.temporal.internal.common.DataConverterUtils;
import io.temporal.proto.failure.Failure;

public abstract class RemoteException extends TemporalException {

  protected final Failure failure;

  protected RemoteException(String message, Failure failure, Exception cause) {
    super(message, cause);
    this.failure = failure;
    if (JAVA_SDK.equals(failure.getSource()) && !Strings.isNullOrEmpty(failure.getStackTrace())) {
      StackTraceElement[] stackTrace = DataConverterUtils.parseStackTrace(failure.getStackTrace());
      if (stackTrace != null) {
        setStackTrace(stackTrace);
      }
    }
  }

  public Failure getFailure() {
    return failure;
  }

  @Override
  public String toString() {
    String s = getClass().getName();
    if (failure.getSource().equals(JAVA_SDK) || Strings.isNullOrEmpty(failure.getStackTrace())) {
      return super.toString();
    }
    return s + ": " + getMessage() + "\n" + failure.getStackTrace();
  }
}
