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
import com.uber.cadence.serviceclient.WorkflowServiceTChannel;
import java.util.Objects;

public final class PollDecisionTaskDispatcherFactory
    implements DispatcherFactory<String, PollForDecisionTaskResponse> {
  private IWorkflowService service = new WorkflowServiceTChannel();
  private Thread.UncaughtExceptionHandler uncaughtExceptionHandler;

  public PollDecisionTaskDispatcherFactory(
      IWorkflowService service, Thread.UncaughtExceptionHandler handler) {
    this.service = Objects.requireNonNull(service);
    uncaughtExceptionHandler = handler;
  }

  public PollDecisionTaskDispatcherFactory(IWorkflowService service) {
    this.service = Objects.requireNonNull(service);
  }

  public PollDecisionTaskDispatcherFactory() {}

  @Override
  public Dispatcher<String, PollForDecisionTaskResponse> create() {
    return new PollDecisionTaskDispatcher(service, uncaughtExceptionHandler);
  }
}
