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

import io.temporal.proto.failure.Failure;
import java.util.Optional;

public abstract class TemporalFailure extends TemporalException {
  private Optional<Failure> failure = Optional.empty();
  private final String originalMessage;

  protected TemporalFailure(String message, String originalMessage, Throwable cause) {
    super(message, cause);
    this.originalMessage = originalMessage;
  }

  Optional<Failure> getFailure() {
    return failure;
  }

  void setFailure(Failure failure) {
    this.failure = Optional.of(failure);
  }

  public String getOriginalMessage() {
    return originalMessage == null ? "" : originalMessage;
  }
}
