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

package io.temporal.serviceclient;

import io.grpc.ManagedChannel;
import io.temporal.proto.workflowservice.WorkflowServiceGrpc;
import java.util.concurrent.TimeUnit;

public interface WorkflowServiceStubs {

  String LOCAL_DOCKER_TARGET = "127.0.0.1:7233";

  static WorkflowServiceStubs newInstance(String target) {
    return new WorkflowServiceStubsImpl(target, WorkflowServiceStubsOptions.getDefaultInstance());
  }

  static WorkflowServiceStubs newInstance(String target, WorkflowServiceStubsOptions options) {
    return new WorkflowServiceStubsImpl(target, options);
  }

  static WorkflowServiceStubs newInstance(ManagedChannel channel) {
    return new WorkflowServiceStubsImpl(channel, WorkflowServiceStubsOptions.getDefaultInstance());
  }

  static WorkflowServiceStubs newInstance(
      ManagedChannel channel, WorkflowServiceStubsOptions options) {
    return new WorkflowServiceStubsImpl(channel, options);
  }

  /** @return Blocking (synchronous) stub that allows direct calls to service. */
  WorkflowServiceGrpc.WorkflowServiceBlockingStub blockingStub();

  /** @return Future (asynchronous) stub that allows direct calls to service. */
  WorkflowServiceGrpc.WorkflowServiceFutureStub futureStub();

  void shutdown();

  void shutdownNow();

  boolean isShutdown();

  boolean isTerminated();

  boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException;
}
