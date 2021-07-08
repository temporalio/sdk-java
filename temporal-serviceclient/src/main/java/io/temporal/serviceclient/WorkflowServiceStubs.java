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
import java.util.concurrent.TimeUnit;

/** Initializes and holds gRPC blocking and future stubs. */
public interface WorkflowServiceStubs {

  /**
   * Create gRPC connection stubs using default options. The options default to the connection to
   * the locally running temporal service.
   */
  static WorkflowServiceStubs newInstance() {
    return newInstance(WorkflowServiceStubsOptions.getDefaultInstance());
  }

  /** Create gRPC connection stubs using provided options. */
  static WorkflowServiceStubs newInstance(WorkflowServiceStubsOptions options) {
    return newInstance(null, options);
  }

  /**
   * Create gRPC connection stubs that connect to the provided service implementation using an
   * in-memory channel. Useful for testing, usually with mock and spy services.
   */
  static WorkflowServiceStubs newInstance(
      WorkflowServiceGrpc.WorkflowServiceImplBase service, WorkflowServiceStubsOptions options) {
    enforceNonWorkflowThread();
    return WorkflowThreadMarker.protectFromWorkflowThread(
        new WorkflowServiceStubsImpl(service, options), WorkflowServiceStubs.class);
  }

  /** @return Blocking (synchronous) stub that allows direct calls to service. */
  WorkflowServiceGrpc.WorkflowServiceBlockingStub blockingStub();

  /** @return Future (asynchronous) stub that allows direct calls to service. */
  WorkflowServiceGrpc.WorkflowServiceFutureStub futureStub();

  void shutdown();

  void shutdownNow();

  boolean isShutdown();

  boolean isTerminated();

  /**
   * Awaits for gRPC stubs shutdown up to the specified timeout. The shutdown has to be initiated
   * through {@link #shutdown()} or {@link #shutdownNow()}.
   *
   * @return false if timed out or the thread was interrupted.
   */
  boolean awaitTermination(long timeout, TimeUnit unit);

  WorkflowServiceStubsOptions getOptions();
}
