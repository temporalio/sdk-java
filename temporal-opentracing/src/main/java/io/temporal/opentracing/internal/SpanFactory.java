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
import io.temporal.opentracing.StartSpanContext;
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
    StartSpanContext context =
        new StartSpanContext(options, operationType, workflowType, workflowId, null, null, null);
    Map<String, String> tags = options.getOperationNameAndTagsProvider().getSpanTags(context);
    String operationName = options.getOperationNameAndTagsProvider().getSpanName(context);
    return createSpan(tracer, startTimeMs, operationName, tags, null, References.FOLLOWS_FROM);
  }

  public Tracer.SpanBuilder createChildWorkflowStartSpan(
      Tracer tracer,
      String childWorkflowType,
      long startTimeMs,
      String workflowId,
      String parentWorkflowId,
      String parentRunId) {
    StartSpanContext context =
        new StartSpanContext(
            options,
            SpanOperationType.START_CHILD_WORKFLOW,
            childWorkflowType,
            workflowId,
            null,
            parentWorkflowId,
            parentRunId);
    Map<String, String> tags = options.getOperationNameAndTagsProvider().getSpanTags(context);
    String operationName = options.getOperationNameAndTagsProvider().getSpanName(context);
    return createSpan(tracer, startTimeMs, operationName, tags, null, References.FOLLOWS_FROM);
  }

  public Tracer.SpanBuilder createWorkflowRunSpan(
      Tracer tracer,
      String workflowType,
      long startTimeMs,
      String workflowId,
      String runId,
      SpanContext workflowStartSpanContext) {
    StartSpanContext context =
        new StartSpanContext(
            options, SpanOperationType.RUN_WORKFLOW, workflowType, workflowId, runId, null, null);
    Map<String, String> tags = options.getOperationNameAndTagsProvider().getSpanTags(context);
    String operationName = options.getOperationNameAndTagsProvider().getSpanName(context);
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
    StartSpanContext context =
        new StartSpanContext(
            options, SpanOperationType.START_ACTIVITY, activityType, workflowId, runId, null, null);
    Map<String, String> tags = options.getOperationNameAndTagsProvider().getSpanTags(context);
    String operationName = options.getOperationNameAndTagsProvider().getSpanName(context);
    return createSpan(tracer, startTimeMs, operationName, tags, null, References.CHILD_OF);
  }

  public Tracer.SpanBuilder createActivityRunSpan(
      Tracer tracer,
      String activityType,
      long startTimeMs,
      String workflowId,
      String runId,
      SpanContext activityStartSpanContext) {
    StartSpanContext context =
        new StartSpanContext(
            options, SpanOperationType.RUN_ACTIVITY, activityType, workflowId, runId, null, null);
    Map<String, String> tags = options.getOperationNameAndTagsProvider().getSpanTags(context);
    String operationName = options.getOperationNameAndTagsProvider().getSpanName(context);
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
