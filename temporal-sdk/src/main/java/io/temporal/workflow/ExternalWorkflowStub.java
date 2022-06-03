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

package io.temporal.workflow;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.internal.sync.StubMarker;

/**
 * Supports signalling and cancelling any workflows by the workflow type and their id. This is
 * useful when an external workflow type is not known at the compile time and to call workflows in
 * other languages.
 *
 * @see Workflow#newUntypedExternalWorkflowStub(String)
 */
public interface ExternalWorkflowStub {

  /**
   * Extracts untyped ExternalWorkflowStub from a typed workflow stub created through {@link
   * Workflow#newExternalWorkflowStub(Class, String)}.
   *
   * @param typed typed external workflow stub
   * @param <T> type of the workflow stub interface
   * @return untyped external workflow stub for the same workflow instance.
   */
  static <T> ExternalWorkflowStub fromTyped(T typed) {
    if (!(typed instanceof StubMarker)) {
      throw new IllegalArgumentException(
          "arguments must be created through Workflow.newChildWorkflowStub");
    }
    if (typed instanceof ChildWorkflowStub) {
      throw new IllegalArgumentException(
          "Use ChildWorkflowStub.fromTyped to extract sbub created through Workflow#newChildWorkflowStub");
    }
    @SuppressWarnings("unchecked")
    StubMarker supplier = (StubMarker) typed;
    return (ExternalWorkflowStub) supplier.__getUntypedStub();
  }

  WorkflowExecution getExecution();

  void signal(String signalName, Object... args);

  void cancel();
}
