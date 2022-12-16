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

package io.temporal.opentracing.internal;

import com.google.common.base.MoreObjects;
import com.google.common.base.Throwables;
import io.opentracing.References;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.opentracing.Tracer.SpanBuilder;
import io.opentracing.log.Fields;
import io.opentracing.tag.Tags;
import io.temporal.opentracing.OpenTracingOptions;
import io.temporal.opentracing.SpanCreationContext;
import io.temporal.opentracing.SpanOperationType;
import io.temporal.opentracing.StandardTagNames;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;

public class SpanFactory {
  // Inspired by convention used in JAX-RS2 OpenTracing implementation:
  // https://github.com/opentracing-contrib/java-jaxrs/blob/dcbfda6/opentracing-jaxrs2/src/main/java/io/opentracing/contrib/jaxrs2/server/OperationNameProvider.java#L46
  private final OpenTracingOptions options;

  public SpanFactory(OpenTracingOptions options) {
    this.options = options;
  }

  public Tracer.SpanBuilder createWorkflowStartSpan(
      Tracer tracer, SpanOperationType operationType, String workflowType, String workflowId) {

    SpanCreationContext context =
        SpanCreationContext.newBuilder()
            .setSpanOperationType(operationType)
            .setActionName(workflowType)
            .setWorkflowId(workflowId)
            .build();

    return createSpan(context, tracer, null, References.FOLLOWS_FROM);
  }

  public Tracer.SpanBuilder createChildWorkflowStartSpan(
      Tracer tracer,
      String childWorkflowType,
      String childWorkflowId,
      long startTimeMs,
      String parentWorkflowId,
      String parentRunId) {
    SpanCreationContext context =
        SpanCreationContext.newBuilder()
            .setSpanOperationType(SpanOperationType.START_CHILD_WORKFLOW)
            .setActionName(childWorkflowType)
            .setWorkflowId(childWorkflowId)
            .setParentWorkflowId(parentWorkflowId)
            .setParentRunId(parentRunId)
            .build();
    return createSpan(context, tracer, null, References.CHILD_OF);
  }

  public Tracer.SpanBuilder createContinueAsNewWorkflowStartSpan(
      Tracer tracer, String continueAsNewWorkflowType, String workflowId, String parentRunId) {
    SpanCreationContext context =
        SpanCreationContext.newBuilder()
            .setSpanOperationType(SpanOperationType.START_CONTINUE_AS_NEW_WORKFLOW)
            .setActionName(continueAsNewWorkflowType)
            .setWorkflowId(workflowId)
            .setParentRunId(parentRunId)
            .build();
    return createSpan(context, tracer, null, References.FOLLOWS_FROM);
  }

  public Tracer.SpanBuilder createWorkflowRunSpan(
      Tracer tracer,
      String workflowType,
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
    return createSpan(context, tracer, workflowStartSpanContext, References.FOLLOWS_FROM);
  }

  public Tracer.SpanBuilder createActivityStartSpan(
      Tracer tracer, String activityType, String workflowId, String runId) {
    SpanCreationContext context =
        SpanCreationContext.newBuilder()
            .setSpanOperationType(SpanOperationType.START_ACTIVITY)
            .setActionName(activityType)
            .setWorkflowId(workflowId)
            .setRunId(runId)
            .build();
    return createSpan(context, tracer, null, References.CHILD_OF);
  }

  public Tracer.SpanBuilder createActivityRunSpan(
      Tracer tracer,
      String activityType,
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
    return createSpan(context, tracer, activityStartSpanContext, References.FOLLOWS_FROM);
  }

  @SuppressWarnings("deprecation")
  public void logFail(Span toSpan, Throwable failReason) {
    toSpan.setTag(StandardTagNames.FAILED, true);
    toSpan.setTag(Tags.ERROR, options.getIsErrorPredicate().test(failReason));

    Map<String, Object> logPayload = new HashMap<>();
    logPayload.put(Fields.EVENT, "error");
    logPayload.put(Fields.ERROR_KIND, failReason.getClass().getName());
    logPayload.put(Fields.ERROR_OBJECT, failReason);
    logPayload.put(Fields.STACK, Throwables.getStackTraceAsString(failReason));

    String message = failReason.getMessage();
    if (message != null) {
      logPayload.put(Fields.MESSAGE, message);
    }

    toSpan.log(System.currentTimeMillis(), logPayload);
  }

  public void logEviction(Span toSpan) {
    toSpan.setTag(StandardTagNames.EVICTED, true);
  }

  private Tracer.SpanBuilder createSpan(
      SpanCreationContext context,
      Tracer tracer,
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

    if (parent != null) {
      builder.addReference(
          MoreObjects.firstNonNull(parentReferenceType, References.FOLLOWS_FROM), parent);
    }

    return builder;
  }
}
