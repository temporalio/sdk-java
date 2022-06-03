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

import io.temporal.api.operatorservice.v1.OperatorServiceGrpc;
import io.temporal.internal.WorkflowThreadMarker;

/** Initializes and holds gRPC blocking and future stubs. */
public interface OperatorServiceStubs
    extends ServiceStubs<
        OperatorServiceGrpc.OperatorServiceBlockingStub,
        OperatorServiceGrpc.OperatorServiceFutureStub> {
  String HEALTH_CHECK_SERVICE_NAME = "temporal.api.operatorservice.v1.OperatorService";

  /**
   * @deprecated use {@link #newLocalServiceStubs()}
   */
  static OperatorServiceStubs newInstance() {
    return newLocalServiceStubs();
  }

  /**
   * @deprecated use {@link #newServiceStubs(OperatorServiceStubsOptions)}
   */
  @Deprecated
  static OperatorServiceStubs newInstance(OperatorServiceStubsOptions options) {
    return newServiceStubs(options);
  }

  /**
   * Creates OperatorService gRPC stubs pointed on to the locally running Temporal Server. The
   * Server should be available on 127.0.0.1:7233
   */
  static OperatorServiceStubs newLocalServiceStubs() {
    return newServiceStubs(OperatorServiceStubsOptions.getDefaultInstance());
  }

  /**
   * Creates OperatorService gRPC stubs<br>
   * This method creates stubs with lazy connectivity, connection is not performed during the
   * creation time and happens on the first request.
   *
   * @param options stub options to use
   */
  static OperatorServiceStubs newServiceStubs(OperatorServiceStubsOptions options) {
    enforceNonWorkflowThread();
    return WorkflowThreadMarker.protectFromWorkflowThread(
        new OperatorServiceStubsImpl(options), OperatorServiceStubs.class);
  }
}
