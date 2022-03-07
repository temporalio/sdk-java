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

package io.temporal.serviceclient;

import static io.temporal.internal.WorkflowThreadMarker.enforceNonWorkflowThread;

import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import io.temporal.internal.WorkflowThreadMarker;
import io.temporal.internal.testservice.InProcessGRPCServer;

/** Initializes and holds gRPC blocking and future stubs. */
public interface WorkflowServiceStubs
    extends ServiceStubs<
        WorkflowServiceGrpc.WorkflowServiceBlockingStub,
        WorkflowServiceGrpc.WorkflowServiceFutureStub> {
  String HEALTH_CHECK_SERVICE_NAME = "temporal.api.workflowservice.v1.WorkflowService";

  /**
   * Create gRPC connection stubs using default options. The options default to the connection to
   * the locally running temporal service.
   */
  static WorkflowServiceStubs newInstance() {
    return newInstance(WorkflowServiceStubsOptions.getDefaultInstance());
  }

  /** Create gRPC connection stubs using provided options. */
  static WorkflowServiceStubs newInstance(WorkflowServiceStubsOptions options) {
    enforceNonWorkflowThread();
    return WorkflowThreadMarker.protectFromWorkflowThread(
        new WorkflowServiceStubsImpl(null, options), WorkflowServiceStubs.class);
  }

  /**
   * Create gRPC connection stubs that connect to the provided service implementation using an
   * in-memory channel. Useful for testing, usually with mock and spy services.
   *
   * @deprecated use {@link InProcessGRPCServer} to manage in-memory server and corresponded channel
   *     outside the stubs. Channel provided by {@link InProcessGRPCServer} should be supplied into
   *     {@link #newInstance(WorkflowServiceStubsOptions)} by specifying {{@link
   *     WorkflowServiceStubsOptions#getChannel()}}
   */
  @Deprecated
  static WorkflowServiceStubs newInstance(
      WorkflowServiceGrpc.WorkflowServiceImplBase service, WorkflowServiceStubsOptions options) {
    enforceNonWorkflowThread();
    return WorkflowThreadMarker.protectFromWorkflowThread(
        new WorkflowServiceStubsImpl(service, options), WorkflowServiceStubs.class);
  }

  WorkflowServiceStubsOptions getOptions();
}
