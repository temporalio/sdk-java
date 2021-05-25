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

package io.temporal.opentracing.internal;

import com.google.common.collect.ImmutableMap;
import io.temporal.opentracing.OperationNameAndTagsProvider;
import io.temporal.opentracing.StandardTagNames;
import io.temporal.opentracing.StartSpanContext;
import java.util.HashMap;
import java.util.Map;

public class DefaultOperationNameAndTagsProvider implements OperationNameAndTagsProvider {

  private static final String PREFIX_DELIMITER = ":";

  @Override
  public String getSpanName(StartSpanContext context) {
    return context.getOptions().getSpanOperationNamePrefix(context.getSpanOperationType())
        + PREFIX_DELIMITER
        + context.getTypeName();
  }

  @Override
  public Map<String, String> getSpanTags(StartSpanContext context) {
    switch (context.getSpanOperationType()) {
      case START_WORKFLOW:
        return ImmutableMap.of(StandardTagNames.WORKFLOW_ID, context.getWorkflowId());
      case START_CHILD_WORKFLOW:
        return ImmutableMap.of(
            StandardTagNames.WORKFLOW_ID, context.getWorkflowId(),
            StandardTagNames.PARENT_WORKFLOW_ID, context.getParentWorkflowId(),
            StandardTagNames.PARENT_RUN_ID, context.getParentRunId());
      case RUN_WORKFLOW:
      case START_ACTIVITY:
      case RUN_ACTIVITY:
        return ImmutableMap.of(
            StandardTagNames.WORKFLOW_ID, context.getWorkflowId(),
            StandardTagNames.RUN_ID, context.getRunId());
    }
    return new HashMap<>();
  }
}
