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

package io.temporal.payload.context;

import io.temporal.common.Experimental;
import javax.annotation.Nonnull;

@Experimental
public class WorkflowSerializationContext implements SerializationContext {
  private final @Nonnull String namespace;
  private final @Nonnull String workflowId;
  // We can't currently reliably and consistency provide workflowType to the DataConverter.
  // 1. Signals and queries don't know workflowType when they are sent.
  // 2. WorkflowStub#getResult call is not aware of the workflowType, workflowType is an optional
  // parameter for a workflow stub that is not used to start a workflow.
  //    private final @Nullable String workflowTypeName;

  public WorkflowSerializationContext(@Nonnull String namespace, @Nonnull String workflowId) {
    this.namespace = namespace;
    this.workflowId = workflowId;
  }

  @Nonnull
  public String getNamespace() {
    return namespace;
  }

  @Nonnull
  public String getWorkflowId() {
    return workflowId;
  }
}
