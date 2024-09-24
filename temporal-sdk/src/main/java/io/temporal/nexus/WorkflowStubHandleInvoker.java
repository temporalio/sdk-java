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

package io.temporal.nexus;

import static io.temporal.internal.common.InternalUtils.createNexusBoundStub;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowStub;
import io.temporal.internal.client.NexusStartWorkflowRequest;

class WorkflowStubHandleInvoker implements WorkflowHandleInvoker {
  final Object[] args;
  final WorkflowStub stub;

  WorkflowStubHandleInvoker(WorkflowStub stub, Object[] args) {
    this.args = args;
    this.stub = stub;
  }

  @Override
  public WorkflowExecution invoke(NexusStartWorkflowRequest request) {
    return createNexusBoundStub(stub, request).start(args);
  }
}
