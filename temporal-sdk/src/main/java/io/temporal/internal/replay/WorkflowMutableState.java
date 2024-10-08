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

import io.temporal.api.command.v1.ContinueAsNewWorkflowExecutionCommandAttributes;
import io.temporal.api.common.v1.Memo;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.SearchAttributes;
import io.temporal.api.history.v1.WorkflowExecutionStartedEventAttributes;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

class WorkflowMutableState {
  private boolean cancelRequested;
  private ContinueAsNewWorkflowExecutionCommandAttributes continueAsNewOnCompletion;
  private boolean workflowMethodCompleted;
  private Throwable workflowTaskFailureThrowable;
  private SearchAttributes.Builder searchAttributes;
  private Memo.Builder memo;

  WorkflowMutableState(WorkflowExecutionStartedEventAttributes startedAttributes) {
    if (startedAttributes.hasSearchAttributes()) {
      this.searchAttributes = startedAttributes.getSearchAttributes().toBuilder();
    }
    if (startedAttributes.hasMemo()) {
      this.memo = startedAttributes.getMemo().toBuilder();
    } else {
      this.memo = Memo.newBuilder();
    }
  }

  boolean isCancelRequested() {
    return cancelRequested;
  }

  void setCancelRequested() {
    cancelRequested = true;
  }

  ContinueAsNewWorkflowExecutionCommandAttributes getContinueAsNewOnCompletion() {
    return continueAsNewOnCompletion;
  }

  void continueAsNewOnCompletion(ContinueAsNewWorkflowExecutionCommandAttributes parameters) {
    this.continueAsNewOnCompletion = parameters;
  }

  Throwable getWorkflowTaskFailure() {
    return workflowTaskFailureThrowable;
  }

  void failWorkflowTask(Throwable failure) {
    workflowTaskFailureThrowable = failure;
  }

  boolean isWorkflowMethodCompleted() {
    return workflowMethodCompleted;
  }

  void setWorkflowMethodCompleted() {
    this.workflowMethodCompleted = true;
  }

  SearchAttributes getSearchAttributes() {
    return searchAttributes == null || searchAttributes.getIndexedFieldsCount() == 0
        ? null
        : searchAttributes.build();
  }

  public Payload getMemo(String key) {
    return memo.build().getFieldsMap().get(key);
  }

  void upsertSearchAttributes(@Nullable SearchAttributes searchAttributes) {
    if (searchAttributes == null || searchAttributes.getIndexedFieldsCount() == 0) {
      return;
    }
    if (this.searchAttributes == null) {
      this.searchAttributes = SearchAttributes.newBuilder();
    }
    this.searchAttributes.putAllIndexedFields(searchAttributes.getIndexedFieldsMap());
  }

  public void upsertMemo(@Nonnull Memo memo) {
    this.memo.putAllFields(memo.getFieldsMap());
  }
}
