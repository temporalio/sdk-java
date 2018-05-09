/*
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

package com.uber.cadence.internal.replay;

import com.uber.cadence.HistoryEvent;
import com.uber.cadence.PollForDecisionTaskResponse;
import com.uber.cadence.WorkflowExecutionStartedEventAttributes;

final class WorkflowContext {

  private final PollForDecisionTaskResponse decisionTask;
  private boolean cancelRequested;
  private ContinueAsNewWorkflowExecutionParameters continueAsNewOnCompletion;
  private WorkflowExecutionStartedEventAttributes startedAttributes;
  private final String domain;

  WorkflowContext(
      String domain,
      PollForDecisionTaskResponse decisionTask,
      WorkflowExecutionStartedEventAttributes startedAttributes) {
    this.domain = domain;
    this.decisionTask = decisionTask;
    this.startedAttributes = startedAttributes;
  }

  com.uber.cadence.WorkflowExecution getWorkflowExecution() {
    return decisionTask.getWorkflowExecution();
  }

  com.uber.cadence.WorkflowType getWorkflowType() {
    return decisionTask.getWorkflowType();
  }

  boolean isCancelRequested() {
    return cancelRequested;
  }

  void setCancelRequested(boolean flag) {
    cancelRequested = flag;
  }

  ContinueAsNewWorkflowExecutionParameters getContinueAsNewOnCompletion() {
    return continueAsNewOnCompletion;
  }

  void setContinueAsNewOnCompletion(ContinueAsNewWorkflowExecutionParameters continueParameters) {
    if (continueParameters == null) {
      continueParameters = new ContinueAsNewWorkflowExecutionParameters();
    }
    //            continueParameters.setChildPolicy(startedAttributes);
    if (continueParameters.getExecutionStartToCloseTimeoutSeconds() == 0) {
      continueParameters.setExecutionStartToCloseTimeoutSeconds(
          startedAttributes.getExecutionStartToCloseTimeoutSeconds());
    }
    if (continueParameters.getTaskList() == null) {
      continueParameters.setTaskList(startedAttributes.getTaskList().getName());
    }
    if (continueParameters.getTaskStartToCloseTimeoutSeconds() == 0) {
      continueParameters.setTaskStartToCloseTimeoutSeconds(
          startedAttributes.getTaskStartToCloseTimeoutSeconds());
    }
    this.continueAsNewOnCompletion = continueParameters;
  }

  // TODO: Implement as soon as WorkflowExecutionStartedEventAttributes have these fields added.
  ////    WorkflowExecution getParentWorkflowExecution() {
  //        WorkflowExecutionStartedEventAttributes attributes =
  // getWorkflowStartedEventAttributes();
  //        return attributes.getParentWorkflowExecution();
  //    }

  ////    com.uber.cadence.ChildPolicy getChildPolicy() {
  //        WorkflowExecutionStartedEventAttributes attributes =
  // getWorkflowStartedEventAttributes();
  //        return ChildPolicy.fromValue(attributes.getChildPolicy());
  //    }

  ////    String getContinuedExecutionRunId() {
  //        WorkflowExecutionStartedEventAttributes attributes =
  // getWorkflowStartedEventAttributes();
  //        return attributes.getContinuedExecutionRunId();
  //    }

  int getExecutionStartToCloseTimeoutSeconds() {
    WorkflowExecutionStartedEventAttributes attributes = getWorkflowStartedEventAttributes();
    return attributes.getExecutionStartToCloseTimeoutSeconds();
  }

  int getDecisionTaskTimeoutSeconds() {
    return startedAttributes.getTaskStartToCloseTimeoutSeconds();
  }

  String getTaskList() {
    WorkflowExecutionStartedEventAttributes attributes = getWorkflowStartedEventAttributes();
    return attributes.getTaskList().getName();
  }

  String getDomain() {
    return domain;
  }

  private WorkflowExecutionStartedEventAttributes getWorkflowStartedEventAttributes() {
    HistoryEvent firstHistoryEvent = decisionTask.getHistory().getEvents().get(0);
    return firstHistoryEvent.getWorkflowExecutionStartedEventAttributes();
  }
}
