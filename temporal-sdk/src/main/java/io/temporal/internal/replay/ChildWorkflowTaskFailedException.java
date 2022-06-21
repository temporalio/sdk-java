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

package io.temporal.internal.replay;

import io.temporal.api.failure.v1.Failure;
import io.temporal.failure.ChildWorkflowFailure;

/**
 * Internal. Do not catch or throw by application level code. Used by the child workflow state
 * machines in case of child workflow task execution failure and contains an original unparsed
 * Failure message with details from the attributes in the exception.
 *
 * <p>This class is needed to don't make Failure -> Exception conversion inside the state machines.
 * So the state machine forms ChildWorkflowFailure without cause and parse the original Failure, so
 * the outside code may join them together.
 */
public class ChildWorkflowTaskFailedException extends RuntimeException {

  private final ChildWorkflowFailure exception;

  private final Failure originalCauseFailure;

  public ChildWorkflowTaskFailedException(
      ChildWorkflowFailure exception, Failure originalCauseFailure) {
    this.exception = exception;
    this.originalCauseFailure = originalCauseFailure;
  }

  public ChildWorkflowFailure getException() {
    return exception;
  }

  public Failure getOriginalCauseFailure() {
    return originalCauseFailure;
  }
}
