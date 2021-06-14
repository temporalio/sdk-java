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
import io.opentracing.Tracer;
import io.temporal.opentracing.SpanBuilderProvider;
import io.temporal.opentracing.SpanCreationContext;
import io.temporal.opentracing.SpanOperationType;
import io.temporal.opentracing.StandardTagNames;
import java.util.Map;

/**
 * Default implementation of the {@link SpanBuilderProvider}. Uses both the {@link
 * SpanOperationType} and the {@link SpanCreationContext#getActionName()} as the name of the
 * OpenTracing span, e.g "StartActivity:LoadUsersFromDatabaseActivity". <br>
 * This class also provides any available IDs, such as workflow ID, run ID, or parent workflow/run
 * ID, as tags depending on the context of the operation.
 */
public class ActionTypeAndNameSpanBuilderProvider implements SpanBuilderProvider {

  private static final String PREFIX_DELIMITER = ":";

  public ActionTypeAndNameSpanBuilderProvider() {}

  public Tracer.SpanBuilder createSpanBuilder(Tracer tracer, SpanCreationContext context) {
    Tracer.SpanBuilder spanBuilder = tracer.buildSpan(this.getSpanName(context));

    this.getSpanTags(context).forEach(spanBuilder::withTag);

    return spanBuilder;
  }

  /**
   * Generates the name of the span given the span context.
   *
   * @param context Span creation context
   * @return The span name
   */
  protected String getSpanName(SpanCreationContext context) {
    return context.getSpanOperationType().getDefaultPrefix()
        + PREFIX_DELIMITER
        + context.getActionName();
  }

  /**
   * Generates tags for the span given the span creation context
   *
   * @param context The span creation context
   * @return The map of tags for the span
   */
  protected Map<String, String> getSpanTags(SpanCreationContext context) {
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
      case SIGNAL_WITH_START_WORKFLOW:
        return ImmutableMap.of(
            StandardTagNames.WORKFLOW_ID, context.getWorkflowId(),
            StandardTagNames.RUN_ID, context.getRunId());
    }
    throw new IllegalArgumentException("Unknown span operation type provided");
  }
}
