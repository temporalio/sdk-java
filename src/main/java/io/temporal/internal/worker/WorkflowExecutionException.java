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

package io.temporal.internal.worker;

import io.temporal.proto.common.Payloads;
import java.util.Optional;

/** Internal. Do not throw or catch in application level code. */
public final class WorkflowExecutionException extends RuntimeException {
  private final Optional<Payloads> details;

  public WorkflowExecutionException(String reason, Optional<Payloads> details) {
    super(reason);
    this.details = details;
  }

  public Optional<Payloads> getDetails() {
    return details;
  }

  public String getReason() {
    return getMessage();
  }
}
