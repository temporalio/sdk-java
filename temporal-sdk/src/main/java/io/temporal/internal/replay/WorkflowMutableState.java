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
