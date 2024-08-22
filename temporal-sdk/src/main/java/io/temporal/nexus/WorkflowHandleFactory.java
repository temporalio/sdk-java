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

import io.nexusrpc.handler.OperationContext;
import io.nexusrpc.handler.OperationStartDetails;
import io.temporal.client.WorkflowClient;
import javax.annotation.Nullable;

/**
 * Function interface for {@link
 * WorkflowRunNexusOperationHandler#fromWorkflowHandle(WorkflowHandleFactory)} representing the
 * workflow to associate with each operation call.
 */
@FunctionalInterface
public interface WorkflowHandleFactory<T, R> {
  /**
   * Invoked every operation start call and expected to return a workflow handle to a workflow stub
   * through the provided {@link WorkflowClient}.
   */
  @Nullable
  WorkflowHandle<R> apply(
      OperationContext context, OperationStartDetails details, WorkflowClient client, T input);
}
