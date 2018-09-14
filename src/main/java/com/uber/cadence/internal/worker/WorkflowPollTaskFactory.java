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

package com.uber.cadence.internal.worker;

import com.uber.cadence.PollForDecisionTaskResponse;
import com.uber.cadence.serviceclient.IWorkflowService;
import com.uber.m3.tally.Scope;
import java.util.Objects;
import java.util.function.Supplier;

public class WorkflowPollTaskFactory
    implements Supplier<Poller.PollTask<PollForDecisionTaskResponse>> {

  private final IWorkflowService service;
  private final String domain;
  private final String taskList;
  private final Scope metricScope;
  private final String identity;

  public WorkflowPollTaskFactory(
      IWorkflowService service,
      String domain,
      String taskList,
      Scope metricScope,
      String identity) {
    this.service = Objects.requireNonNull(service, "service should not be null");
    this.domain = Objects.requireNonNull(domain, "domain should not be null");
    this.taskList = Objects.requireNonNull(taskList, "taskList should not be null");
    this.metricScope = Objects.requireNonNull(metricScope, "metricScope should not be null");
    this.identity = Objects.requireNonNull(identity, "identity should not be null");
  }

  @Override
  public Poller.PollTask<PollForDecisionTaskResponse> get() {
    return new WorkflowPollTask(service, domain, taskList, metricScope, identity);
  }
}
