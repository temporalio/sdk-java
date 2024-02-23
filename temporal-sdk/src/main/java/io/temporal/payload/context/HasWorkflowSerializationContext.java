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

import io.temporal.common.converter.DataConverter;

/**
 * {@link SerializationContext} that contains Namespace and Workflow ID of the Serialization Target.
 *
 * <p>This interface is a convenience interface for users that customize {@link DataConverter}s.
 * This interface provides a unified API for {@link SerializationContext} implementations that
 * contain information about the Workflow Execution the Serialization Target belongs to. If some
 * Workflow is Serialization target itself, methods of this interface will return information of
 * that workflow.
 */
public interface HasWorkflowSerializationContext extends SerializationContext {
  /**
   * @return namespace the workflow execution belongs to
   */
  String getNamespace();

  /**
   * @return workflowId of the Workflow Execution the Serialization Target belongs to. If the Target
   *     is a Workflow itself, this method will return the Target's Workflow ID (not the ID of the
   *     parent workflow).
   */
  String getWorkflowId();

  /**
   * @return runId of the Workflow Execution the Serialization Target belongs to, if available. If the Target
   *     is a Workflow itself, this method will return the Target's Run ID (not the RunID of the
   *     parent workflow).
   */
  String getRunId();

}
