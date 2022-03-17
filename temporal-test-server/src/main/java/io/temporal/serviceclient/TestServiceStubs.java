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

import io.temporal.api.testservice.v1.TestServiceGrpc;
import io.temporal.internal.WorkflowThreadMarker;

public interface TestServiceStubs
    extends ServiceStubs<
        TestServiceGrpc.TestServiceBlockingStub, TestServiceGrpc.TestServiceFutureStub> {
  String HEALTH_CHECK_SERVICE_NAME = "temporal.api.testservice.v1.TestService";

  static TestServiceStubs newInstance(TestServiceStubsOptions options) {
    enforceNonWorkflowThread();
    return WorkflowThreadMarker.protectFromWorkflowThread(
        new TestServiceStubsImpl(options), TestServiceStubs.class);
  }
}
