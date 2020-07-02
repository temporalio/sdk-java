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

package io.temporal.workflow;

import io.temporal.common.v1.SearchAttributes;
import java.time.Duration;
import java.util.Optional;

public interface WorkflowInfo {

  String getNamespace();

  String getWorkflowId();

  String getRunId();

  String getWorkflowType();

  Optional<String> getContinuedExecutionRunId();

  String getTaskQueue();

  Duration getWorkflowRunTimeout();

  Duration getWorkflowExecutionTimeout();

  /**
   * The time workflow run has started. Note that this time can be different from the time workflow
   * function started actual execution.
   */
  long getRunStartedTimestampMillis();

  SearchAttributes getSearchAttributes();

  Optional<String> getParentWorkflowId();

  Optional<String> getParentRunId();
}
