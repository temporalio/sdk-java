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

import io.temporal.api.cloud.cloudservice.v1.CloudServiceGrpc;
import io.temporal.internal.WorkflowThreadMarker;

/**
 * Initializes and holds gRPC blocking and future stubs.
 *
 * <p>WARNING: The cloud service is currently experimental.
 */
public interface CloudServiceStubs
    extends ServiceStubs<
        CloudServiceGrpc.CloudServiceBlockingStub, CloudServiceGrpc.CloudServiceFutureStub> {
  String HEALTH_CHECK_SERVICE_NAME = "temporal.api.cloud.cloudservice.v1.CloudService";

  /** Creates CloudService gRPC stubs pointed on to Temporal Cloud. */
  static CloudServiceStubs newCloudServiceStubs() {
    return newServiceStubs(CloudServiceStubsOptions.getDefaultInstance());
  }

  /**
   * Creates CloudService gRPC stubs<br>
   * This method creates stubs with lazy connectivity, connection is not performed during the
   * creation time and happens on the first request.
   *
   * @param options stub options to use
   */
  static CloudServiceStubs newServiceStubs(CloudServiceStubsOptions options) {
    enforceNonWorkflowThread();
    return WorkflowThreadMarker.protectFromWorkflowThread(
        new CloudServiceStubsImpl(options), CloudServiceStubs.class);
  }
}
