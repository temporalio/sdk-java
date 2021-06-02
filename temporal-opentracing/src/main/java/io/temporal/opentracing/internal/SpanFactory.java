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

import com.google.common.base.MoreObjects;
import com.uber.m3.util.ImmutableMap;
import io.opentracing.References;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.opentracing.Tracer.SpanBuilder;
import io.temporal.opentracing.OpenTracingOptions;
import io.temporal.opentracing.SpanCreationContext;
import io.temporal.opentracing.SpanOperationType;
import io.temporal.opentracing.StandardLogNames;
import io.temporal.opentracing.StandardTagNames;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

public class SpanFactory {
  // Inspired by convention used in JAX-RS2 OpenTracing implementation:
  // https://github.com/opentracing-contrib/java-jaxrs/blob/dcbfda6/opentracing-jaxrs2/src/main/java/io/opentracing/contrib/jaxrs2/server/OperationNameProvider.java#L46
  private final OpenTracingOptions options;

  public SpanFactory(OpenTracingOptions options) {
    this.options = options;
  }

  public Tracer.SpanBuilder createWorkflowStartSpan(
      Tracer tracer,
      SpanOperationType operationType,
      String workflowType,
      long startTimeMs,
      String workflowId) {

    SpanCreationContext context =
        SpanCreationContext.newBuilder()
            .setSpanOperationType(operationType)
            .setActionName(workflowType)
            .setWorkflowId(workflowId)
            .build();

    return createSpan(context, tracer, startTimeMs, null, References.FOLLOWS_FROM);
  }

  public Tracer.SpanBuilder createChildWorkflowStartSpan(
      Tracer tracer,
      String childWorkflowType,
      long startTimeMs,
      String workflowId,
      String parentWorkflowId,
      String parentRunId) {
    SpanCreationContext context =
        SpanCreationContext.newBuilder()
            .setSpanOperationType(SpanOperationType.START_CHILD_WORKFLOW)
            .setActionName(childWorkflowType)
            .setWorkflowId(workflowId)
            .setParentWorkflowId(parentWorkflowId)
            .setParentRunId(parentRunId)
            .build();
    return createSpan(context, tracer, startTimeMs, null, References.FOLLOWS_FROM);
  }

  public Tracer.SpanBuilder createWorkflowRunSpan(
      Tracer tracer,
      String workflowType,
      long startTimeMs,
      String workflowId,
      String runId,
      SpanContext workflowStartSpanContext) {
    SpanCreationContext context =
        SpanCreationContext.newBuilder()
            .setSpanOperationType(SpanOperationType.RUN_WORKFLOW)
            .setActionName(workflowType)
            .setWorkflowId(workflowId)
            .setRunId(runId)
            .build();
    return createSpan(
        context, tracer, startTimeMs, workflowStartSpanContext, References.FOLLOWS_FROM);
  }

  public Tracer.SpanBuilder createActivityStartSpan(
      Tracer tracer, String activityType, long startTimeMs, String workflowId, String runId) {
    SpanCreationContext context =
        SpanCreationContext.newBuilder()
            .setSpanOperationType(SpanOperationType.START_ACTIVITY)
            .setActionName(activityType)
            .setWorkflowId(workflowId)
            .setRunId(runId)
            .build();
    return createSpan(context, tracer, startTimeMs, null, References.CHILD_OF);
  }

  public Tracer.SpanBuilder createActivityRunSpan(
      Tracer tracer,
      String activityType,
      long startTimeMs,
      String workflowId,
      String runId,
      SpanContext activityStartSpanContext) {
    SpanCreationContext context =
        SpanCreationContext.newBuilder()
            .setSpanOperationType(SpanOperationType.RUN_ACTIVITY)
            .setActionName(activityType)
            .setWorkflowId(workflowId)
            .setRunId(runId)
            .build();
    return createSpan(context, tracer, startTimeMs, activityStartSpanContext, References.CHILD_OF);
  }

  public void logFail(Span toSpan, Throwable failReason) {
    toSpan.setTag(StandardTagNames.FAILED, true);
    Map<String, Object> logPayload =
        ImmutableMap.of(
            StandardLogNames.FAILURE_MESSAGE,
            failReason.getMessage(),
            StandardLogNames.FAILURE_CAUSE,
            failReason);
    toSpan.log(System.currentTimeMillis(), logPayload);
  }

  private Tracer.SpanBuilder createSpan(
      SpanCreationContext context,
      Tracer tracer,
      long startTimeMs,
      @Nullable SpanContext parentSpanContext,
      @Nullable String parentReferenceType) {
    SpanContext parent;

    Span activeSpan = tracer.activeSpan();
    if (activeSpan != null) {
      // we prefer an actual opentracing active span if it exists
      parent = activeSpan.context();
    } else {
      // next we try to use the parent span context from parameters
      parent = parentSpanContext;
    }

    SpanBuilder builder = options.getSpanBuilderProvider().createSpanBuilder(tracer, context);

    long startTimeMc = TimeUnit.MILLISECONDS.toMicros(startTimeMs);
    builder.withStartTimestamp(startTimeMc);

    if (parent != null) {
      builder.addReference(
          MoreObjects.firstNonNull(parentReferenceType, References.FOLLOWS_FROM), parent);
    }

    return builder;
  }
}
