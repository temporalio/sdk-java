package io.temporal.opentelemetry.internal;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.temporal.internal.common.FailureUtils;
import io.temporal.opentelemetry.OpenTelemetryOptions;
import io.temporal.opentelemetry.SpanCreationContext;
import io.temporal.opentelemetry.SpanOperationType;
import io.temporal.opentelemetry.StandardTagNames;
import javax.annotation.Nullable;

public class SpanFactory {
  private final OpenTelemetryOptions options;

  public SpanFactory(OpenTelemetryOptions options) {
    this.options = options;
  }

  public SpanBuilder createWorkflowStartSpan(
      Tracer tracer, SpanOperationType operationType, String workflowType, String workflowId) {

    SpanCreationContext context =
        SpanCreationContext.newBuilder()
            .setSpanOperationType(operationType)
            .setActionName(workflowType)
            .setWorkflowId(workflowId)
            .build();

    return createSpan(context, tracer, null);
  }

  public SpanBuilder createStartNexusOperationSpan(
      Tracer tracer, String serviceName, String operationName, String workflowId, String runId) {
    SpanCreationContext context =
        SpanCreationContext.newBuilder()
            .setSpanOperationType(SpanOperationType.START_NEXUS_OPERATION)
            .setActionName(serviceName + "/" + operationName)
            .setWorkflowId(workflowId)
            .setRunId(runId)
            .build();
    return createSpan(context, tracer, null);
  }

  public SpanBuilder createChildWorkflowStartSpan(
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
    return createSpan(context, tracer, null);
  }

  public SpanBuilder createExternalWorkflowSignalSpan(
      Tracer tracer, String signalName, String workflowId, String runId) {
    SpanCreationContext context =
        SpanCreationContext.newBuilder()
            .setSpanOperationType(SpanOperationType.SIGNAL_EXTERNAL_WORKFLOW)
            .setActionName(signalName)
            .setWorkflowId(workflowId)
            .setRunId(runId)
            .build();
    return createSpan(context, tracer, null);
  }

  public SpanBuilder createWorkflowSignalSpan(
      Tracer tracer, String signalName, String workflowId, String runId) {
    SpanCreationContext context =
        SpanCreationContext.newBuilder()
            .setSpanOperationType(SpanOperationType.SIGNAL_WORKFLOW)
            .setActionName(signalName)
            .setWorkflowId(workflowId)
            .setRunId(runId)
            .build();
    return createSpan(context, tracer, null);
  }

  public SpanBuilder createWorkflowHandleSignalSpan(
      Tracer tracer, String signalName, String workflowId, String runId, Context parentContext) {
    SpanCreationContext context =
        SpanCreationContext.newBuilder()
            .setSpanOperationType(SpanOperationType.HANDLE_SIGNAL)
            .setActionName(signalName)
            .setWorkflowId(workflowId)
            .setRunId(runId)
            .build();
    return createSpan(context, tracer, parentContext);
  }

  public SpanBuilder createContinueAsNewWorkflowStartSpan(
      Tracer tracer, String continueAsNewWorkflowType, String workflowId, String parentRunId) {
    SpanCreationContext context =
        SpanCreationContext.newBuilder()
            .setSpanOperationType(SpanOperationType.START_CONTINUE_AS_NEW_WORKFLOW)
            .setActionName(continueAsNewWorkflowType)
            .setWorkflowId(workflowId)
            .setParentRunId(parentRunId)
            .build();
    return createSpan(context, tracer, null);
  }

  public SpanBuilder createWorkflowRunSpan(
      Tracer tracer, String workflowType, String workflowId, String runId, Context parentContext) {
    SpanCreationContext context =
        SpanCreationContext.newBuilder()
            .setSpanOperationType(SpanOperationType.RUN_WORKFLOW)
            .setActionName(workflowType)
            .setWorkflowId(workflowId)
            .setRunId(runId)
            .build();
    return createSpan(context, tracer, parentContext);
  }

  public SpanBuilder createActivityStartSpan(
      Tracer tracer, String activityType, String workflowId, String runId) {
    SpanCreationContext context =
        SpanCreationContext.newBuilder()
            .setSpanOperationType(SpanOperationType.START_ACTIVITY)
            .setActionName(activityType)
            .setWorkflowId(workflowId)
            .setRunId(runId)
            .build();
    return createSpan(context, tracer, null);
  }

  public SpanBuilder createActivityRunSpan(
      Tracer tracer, String activityType, String workflowId, String runId, Context parentContext) {
    SpanCreationContext context =
        SpanCreationContext.newBuilder()
            .setSpanOperationType(SpanOperationType.RUN_ACTIVITY)
            .setActionName(activityType)
            .setWorkflowId(workflowId)
            .setRunId(runId)
            .build();
    return createSpan(context, tracer, parentContext);
  }

  public SpanBuilder createStartNexusOperationSpan(
      Tracer tracer, String serviceName, String operationName, Context parentContext) {
    SpanCreationContext context =
        SpanCreationContext.newBuilder()
            .setSpanOperationType(SpanOperationType.RUN_START_NEXUS_OPERATION)
            .setActionName(serviceName + "/" + operationName)
            .build();
    return createSpan(context, tracer, parentContext);
  }

  public SpanBuilder createCancelNexusOperationSpan(
      Tracer tracer, String serviceName, String operationName, Context parentContext) {
    SpanCreationContext context =
        SpanCreationContext.newBuilder()
            .setSpanOperationType(SpanOperationType.RUN_CANCEL_NEXUS_OPERATION)
            .setActionName(serviceName + "/" + operationName)
            .build();
    return createSpan(context, tracer, parentContext);
  }

  public SpanBuilder createWorkflowStartUpdateSpan(
      Tracer tracer, String updateName, String workflowId, String runId) {
    SpanCreationContext context =
        SpanCreationContext.newBuilder()
            .setSpanOperationType(SpanOperationType.UPDATE_WORKFLOW)
            .setActionName(updateName)
            .setWorkflowId(workflowId)
            .setRunId(runId)
            .build();
    return createSpan(context, tracer, null);
  }

  public SpanBuilder createWorkflowExecuteUpdateSpan(
      Tracer tracer, String updateName, String workflowId, String runId, Context parentContext) {
    SpanCreationContext context =
        SpanCreationContext.newBuilder()
            .setSpanOperationType(SpanOperationType.HANDLE_UPDATE)
            .setActionName(updateName)
            .setWorkflowId(workflowId)
            .setRunId(runId)
            .build();
    return createSpan(context, tracer, parentContext);
  }

  public SpanBuilder createWorkflowQuerySpan(
      Tracer tracer, String queryName, String workflowId, String runId) {
    SpanCreationContext context =
        SpanCreationContext.newBuilder()
            .setSpanOperationType(SpanOperationType.QUERY_WORKFLOW)
            .setActionName(queryName)
            .setWorkflowId(workflowId)
            .setRunId(runId)
            .build();
    return createSpan(context, tracer, null);
  }

  public SpanBuilder createWorkflowHandleQuerySpan(
      Tracer tracer, String queryName, Context parentContext) {
    SpanCreationContext context =
        SpanCreationContext.newBuilder()
            .setSpanOperationType(SpanOperationType.HANDLE_QUERY)
            .setActionName(queryName)
            .build();
    return createSpan(context, tracer, parentContext);
  }

  public void logFail(Span span, Throwable throwable) {
    if (throwable != null) {
      span.recordException(throwable);
      if (!FailureUtils.isBenignApplicationFailure(throwable)) {
        if (options.getIsErrorPredicate().test(throwable)) {
          span.setStatus(StatusCode.ERROR);
        }
      }
    }
    span.setAttribute(StandardTagNames.FAILED, true);
  }

  public void logEviction(Span span) {
    span.setAttribute(StandardTagNames.EVICTED, true);
  }

  private SpanBuilder createSpan(
      SpanCreationContext context, Tracer tracer, @Nullable Context parentContext) {

    SpanBuilder builder = options.getSpanBuilderProvider().createSpanBuilder(tracer, context);

    // Set the parent context if provided
    if (parentContext != null) {
      builder.setParent(parentContext);
    }

    // Client operations should use CLIENT kind, server operations should use SERVER kind
    if (context.getSpanOperationType().name().startsWith("START_")
        || context.getSpanOperationType() == SpanOperationType.SIGNAL_EXTERNAL_WORKFLOW
        || context.getSpanOperationType() == SpanOperationType.QUERY_WORKFLOW
        || context.getSpanOperationType() == SpanOperationType.UPDATE_WORKFLOW) {
      builder.setSpanKind(SpanKind.CLIENT);
    } else {
      builder.setSpanKind(SpanKind.SERVER);
    }

    return builder;
  }
}
