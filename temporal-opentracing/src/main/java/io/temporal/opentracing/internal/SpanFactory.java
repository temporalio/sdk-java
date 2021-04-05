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
import io.temporal.opentracing.OpenTracingOptions;
import io.temporal.opentracing.SpanOperationType;
import io.temporal.opentracing.StandardLogNames;
import io.temporal.opentracing.StandardTagNames;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

public class SpanFactory {
  // Inspired by convention used in JAX-RS2 OpenTracing implementation:
  // https://github.com/opentracing-contrib/java-jaxrs/blob/dcbfda6/opentracing-jaxrs2/src/main/java/io/opentracing/contrib/jaxrs2/server/OperationNameProvider.java#L46
  private static final String PREFIX_DELIMITER = ":";

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
    Map<String, String> tags = ImmutableMap.of(StandardTagNames.WORKFLOW_ID, workflowId);
    String operationName =
        options.getSpanOperationNamePrefix(operationType) + PREFIX_DELIMITER + workflowType;
    return createSpan(tracer, startTimeMs, operationName, tags, null, References.FOLLOWS_FROM);
  }

  public Tracer.SpanBuilder createChildWorkflowStartSpan(
      Tracer tracer,
      String childWorkflowType,
      long startTimeMs,
      String workflowId,
      String parentWorkflowId,
      String parentRunId) {
    Map<String, String> tags =
        ImmutableMap.of(
            StandardTagNames.WORKFLOW_ID, workflowId,
            StandardTagNames.PARENT_WORKFLOW_ID, parentWorkflowId,
            StandardTagNames.PARENT_RUN_ID, parentRunId);
    String operationName =
        options.getSpanOperationNamePrefix(SpanOperationType.START_CHILD_WORKFLOW)
            + PREFIX_DELIMITER
            + childWorkflowType;
    return createSpan(tracer, startTimeMs, operationName, tags, null, References.FOLLOWS_FROM);
  }

  public Tracer.SpanBuilder createWorkflowRunSpan(
      Tracer tracer,
      String workflowType,
      long startTimeMs,
      String workflowId,
      String runId,
      SpanContext workflowStartSpanContext) {
    Map<String, String> tags =
        ImmutableMap.of(
            StandardTagNames.WORKFLOW_ID, workflowId,
            StandardTagNames.RUN_ID, runId);
    String operationName =
        options.getSpanOperationNamePrefix(SpanOperationType.RUN_WORKFLOW)
            + PREFIX_DELIMITER
            + workflowType;
    return createSpan(
        tracer,
        startTimeMs,
        operationName,
        tags,
        workflowStartSpanContext,
        References.FOLLOWS_FROM);
  }

  public Tracer.SpanBuilder createActivityStartSpan(
      Tracer tracer, String activityType, long startTimeMs, String workflowId, String runId) {
    Map<String, String> tags =
        ImmutableMap.of(
            StandardTagNames.WORKFLOW_ID, workflowId,
            StandardTagNames.RUN_ID, runId);
    String operationName =
        options.getSpanOperationNamePrefix(SpanOperationType.START_ACTIVITY)
            + PREFIX_DELIMITER
            + activityType;
    return createSpan(tracer, startTimeMs, operationName, tags, null, References.CHILD_OF);
  }

  public Tracer.SpanBuilder createActivityRunSpan(
      Tracer tracer,
      String activityType,
      long startTimeMs,
      String workflowId,
      String runId,
      SpanContext activityStartSpanContext) {
    Map<String, String> tags =
        ImmutableMap.of(
            StandardTagNames.WORKFLOW_ID, workflowId,
            StandardTagNames.RUN_ID, runId);
    String operationName =
        options.getSpanOperationNamePrefix(SpanOperationType.RUN_ACTIVITY)
            + PREFIX_DELIMITER
            + activityType;
    return createSpan(
        tracer, startTimeMs, operationName, tags, activityStartSpanContext, References.CHILD_OF);
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

  private static Tracer.SpanBuilder createSpan(
      Tracer tracer,
      long startTimeMs,
      String operationName,
      Map<String, String> tags,
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

    long startTimeMc = TimeUnit.MILLISECONDS.toMicros(startTimeMs);
    Tracer.SpanBuilder spanBuilder =
        tracer.buildSpan(operationName).withStartTimestamp(startTimeMc);

    if (parent != null) {
      spanBuilder.addReference(
          MoreObjects.firstNonNull(parentReferenceType, References.FOLLOWS_FROM), parent);
    }

    tags.forEach(spanBuilder::withTag);
    return spanBuilder;
  }
}
