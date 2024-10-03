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

package io.temporal.failure;

import io.temporal.common.Experimental;

/**
 * Contains information about a Nexus operation failure. Always contains the original reason for the
 * failure as its cause. For example if a Nexus operation timed out the cause is {@link
 * TimeoutFailure}.
 *
 * <p><b>This exception is only expected to be thrown by the Temporal SDK.</b>
 */
@Experimental
public final class NexusOperationFailure extends TemporalFailure {
  private final long scheduledEventId;
  private final String endpoint;
  private final String service;
  private final String operation;
  private final String operationId;

  public NexusOperationFailure(
      String message,
      long scheduledEventId,
      String endpoint,
      String service,
      String operation,
      String operationId,
      Throwable cause) {
    super(
        getMessage(message, scheduledEventId, endpoint, service, operation, operationId),
        message,
        cause);
    this.scheduledEventId = scheduledEventId;
    this.endpoint = endpoint;
    this.service = service;
    this.operation = operation;
    this.operationId = operationId;
  }

  public static String getMessage(
      String originalMessage,
      long scheduledEventId,
      String endpoint,
      String service,
      String operation,
      String operationId) {
    return "Nexus Operation with operation='"
        + operation
        + "service='"
        + service
        + "' endpoint='"
        + endpoint
        + "' failed: '"
        + originalMessage
        + "'. "
        + "scheduledEventId="
        + scheduledEventId
        + (operationId == null ? "" : ", operationId=" + operationId);
  }

  public long getScheduledEventId() {
    return scheduledEventId;
  }

  public String getEndpoint() {
    return endpoint;
  }

  public String getService() {
    return service;
  }

  public String getOperation() {
    return operation;
  }

  public String getOperationId() {
    return operationId;
  }
}
