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

package io.temporal.serviceclient;

import static io.temporal.internal.WorkflowThreadMarker.enforceNonWorkflowThread;

import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import io.temporal.internal.WorkflowThreadMarker;
import io.temporal.internal.testservice.InProcessGRPCServer;
import java.time.Duration;
import javax.annotation.Nullable;

/** Initializes and holds gRPC blocking and future stubs. */
public interface WorkflowServiceStubs
    extends ServiceStubs<
        WorkflowServiceGrpc.WorkflowServiceBlockingStub,
        WorkflowServiceGrpc.WorkflowServiceFutureStub> {
  String HEALTH_CHECK_SERVICE_NAME = "temporal.api.workflowservice.v1.WorkflowService";

  /**
   * Creates WorkflowService gRPC stubs pointed on to the locally running Temporal Server. The
   * Server should be available on 127.0.0.1:7233
   */
  static WorkflowServiceStubs newLocalServiceStubs() {
    return newServiceStubs(WorkflowServiceStubsOptions.getDefaultInstance());
  }

  /**
   * Creates WorkflowService gRPC stubs.
   *
   * <p>This method creates stubs with "lazy" connectivity. The connection is not performed during
   * the creation time and happens on the first request. <br>
   * If you wish to perform a connection in an eager manner, call {@link
   * WorkflowServiceStubs#connect(Duration)} after creation or use {@link
   * #newConnectedServiceStubs(WorkflowServiceStubsOptions, Duration)} instead of this method.
   *
   * <p>Migration Note: This method doesn't respect {@link
   * WorkflowServiceStubsOptions.Builder#setDisableHealthCheck(boolean)}, {@link
   * WorkflowServiceStubsOptions.Builder#setHealthCheckAttemptTimeout(Duration)} (boolean)} and
   * {@link WorkflowServiceStubsOptions.Builder#setHealthCheckTimeout(Duration)} (boolean)}. This
   * method is equivalent to {@link
   * WorkflowServiceStubsOptions.Builder#setDisableHealthCheck(boolean)} set.
   *
   * @param options stub options to use
   */
  static WorkflowServiceStubs newServiceStubs(WorkflowServiceStubsOptions options) {
    enforceNonWorkflowThread();
    return WorkflowThreadMarker.protectFromWorkflowThread(
        new WorkflowServiceStubsImpl(null, options), WorkflowServiceStubs.class);
  }

  /**
   * Creates WorkflowService gRPC stubs and ensures connectivity with the server at the moment of
   * creation.
   *
   * <p>See {@link #newServiceStubs(WorkflowServiceStubsOptions)} if you prefer a lazy version of
   * this method that doesn't perform an eager connection. This method is functionally equivalent to
   * a sequence of {@link #newServiceStubs(WorkflowServiceStubsOptions)} and {@link
   * WorkflowServiceStubs#connect(Duration)}
   *
   * <p>Migration Note: This method doesn't respect {@link
   * WorkflowServiceStubsOptions.Builder#setDisableHealthCheck(boolean)}, {@link
   * WorkflowServiceStubsOptions.Builder#setHealthCheckAttemptTimeout(Duration)} (boolean)} and
   * {@link WorkflowServiceStubsOptions.Builder#setHealthCheckTimeout(Duration)} (boolean)}. This
   * method is equivalent to {@link
   * WorkflowServiceStubsOptions.Builder#setDisableHealthCheck(boolean)} not set.
   *
   * @param options stub options to use
   * @param timeout timeout to use in {@link WorkflowServiceStubs#connect(Duration)} call. If null,
   *     {@code options.getRpcTimeout()} will be used.
   */
  static WorkflowServiceStubs newConnectedServiceStubs(
      WorkflowServiceStubsOptions options, @Nullable Duration timeout) {
    WorkflowServiceStubs workflowServiceStubs = newServiceStubs(options);
    workflowServiceStubs.connect(timeout);
    return workflowServiceStubs;
  }

  /**
   * Create gRPC connection stubs using default options. The options default to the connection to
   * the locally running temporal service.
   *
   * @deprecated use {@link #newLocalServiceStubs()}.
   */
  @Deprecated
  static WorkflowServiceStubs newInstance() {
    return newInstance(WorkflowServiceStubsOptions.getDefaultInstance());
  }

  /**
   * Create gRPC connection stubs using provided options.
   *
   * @deprecated use {@link #newServiceStubs(WorkflowServiceStubsOptions)} or {@link
   *     #newConnectedServiceStubs(WorkflowServiceStubsOptions, Duration)}. Use {@link
   *     #newServiceStubs(WorkflowServiceStubsOptions)} to get the same behavior as with set {@link
   *     WorkflowServiceStubsOptions.Builder#setDisableHealthCheck(boolean)} (preferred). Use {@link
   *     #newConnectedServiceStubs(WorkflowServiceStubsOptions, Duration)} with {{@link
   *     WorkflowServiceStubsOptions.Builder#setHealthCheckTimeout(Duration)} as {@code timeout}
   *     (null if you didn't specify it).
   */
  @Deprecated
  static WorkflowServiceStubs newInstance(WorkflowServiceStubsOptions options) {
    if (options.getDisableHealthCheck()) {
      return newServiceStubs(options);
    } else {
      return newConnectedServiceStubs(options, options.getHealthCheckTimeout());
    }
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
