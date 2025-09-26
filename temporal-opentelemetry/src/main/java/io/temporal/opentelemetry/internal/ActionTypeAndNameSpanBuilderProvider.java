package io.temporal.opentelemetry.internal;

import com.google.common.collect.ImmutableMap;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.Tracer;
import io.temporal.opentelemetry.SpanBuilderProvider;
import io.temporal.opentelemetry.SpanCreationContext;
import io.temporal.opentelemetry.SpanOperationType;
import io.temporal.opentelemetry.StandardTagNames;
import java.util.Map;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default implementation of the {@link SpanBuilderProvider}. Uses both the {@link
 * SpanOperationType} and the {@link SpanCreationContext#getActionName()} as the name of the
 * OpenTelemetry span, e.g "StartActivity:LoadUsersFromDatabaseActivity". <br>
 * This class also provides any available IDs, such as workflow ID, run ID, or parent workflow/run
 * ID, as attributes depending on the context of the operation.
 */
public class ActionTypeAndNameSpanBuilderProvider implements SpanBuilderProvider {
  private static final Logger logger =
      LoggerFactory.getLogger(ActionTypeAndNameSpanBuilderProvider.class);
  public static final ActionTypeAndNameSpanBuilderProvider INSTANCE =
      new ActionTypeAndNameSpanBuilderProvider();

  private static final String PREFIX_DELIMITER = ":";

  public ActionTypeAndNameSpanBuilderProvider() {}

  public io.opentelemetry.api.trace.SpanBuilder createSpanBuilder(
      Tracer tracer, SpanCreationContext context) {
    SpanBuilder spanBuilder = tracer.spanBuilder(this.getSpanName(context));

    Map<String, String> attributes = getSpanTags(context);
    for (Map.Entry<String, String> entry : attributes.entrySet()) {
      spanBuilder.setAttribute(entry.getKey(), entry.getValue());
    }

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
   * Generates attributes for the span given the span creation context
   *
   * @param context The span creation context
   * @return The map of attributes for the span
   */
  protected Map<String, String> getSpanTags(SpanCreationContext context) {
    SpanOperationType operationType = context.getSpanOperationType();

    // Use a builder approach to safely handle nulls
    ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();

    switch (operationType) {
      case START_WORKFLOW:
      case SIGNAL_WITH_START_WORKFLOW:
        addIfNotNull(builder, StandardTagNames.WORKFLOW_ID, context.getWorkflowId());
        break;
      case START_CHILD_WORKFLOW:
        addIfNotNull(builder, StandardTagNames.WORKFLOW_ID, context.getWorkflowId());
        addIfNotNull(builder, StandardTagNames.PARENT_WORKFLOW_ID, context.getParentWorkflowId());
        addIfNotNull(builder, StandardTagNames.PARENT_RUN_ID, context.getParentRunId());
        break;
      case START_CONTINUE_AS_NEW_WORKFLOW:
        addIfNotNull(builder, StandardTagNames.WORKFLOW_ID, context.getWorkflowId());
        addIfNotNull(builder, StandardTagNames.PARENT_RUN_ID, context.getParentRunId());
        break;
      case RUN_WORKFLOW:
      case START_ACTIVITY:
      case RUN_ACTIVITY:
      case SIGNAL_EXTERNAL_WORKFLOW:
      case SIGNAL_WORKFLOW:
      case UPDATE_WORKFLOW:
      case QUERY_WORKFLOW:
      case HANDLE_SIGNAL:
      case HANDLE_UPDATE:
        String runId = context.getRunId();
        if (runId != null) {
          addIfNotNull(builder, StandardTagNames.WORKFLOW_ID, context.getWorkflowId());
          addIfNotNull(builder, StandardTagNames.RUN_ID, runId);
        } else {
          // Log a warning for operations where runId is expected but missing
          logger.warn("runId is null for span operation type {}", operationType);
        }
        break;
      case START_NEXUS_OPERATION:
        addIfNotNull(builder, StandardTagNames.WORKFLOW_ID, context.getWorkflowId());
        addIfNotNull(builder, StandardTagNames.RUN_ID, context.getRunId());
        break;
      case RUN_START_NEXUS_OPERATION:
      case RUN_CANCEL_NEXUS_OPERATION:
      case HANDLE_QUERY:
        break;
      default:
        throw new IllegalArgumentException("Unknown span operation type provided");
    }

    return builder.build();
  }

  /**
   * Helper method to add a tag to the builder only if the value is not null.
   *
   * @param builder The ImmutableMap.Builder to add to
   * @param key The tag key
   * @param value The tag value (can be null)
   */
  private void addIfNotNull(
      ImmutableMap.Builder<String, String> builder, String key, @Nullable String value) {
    if (value != null) {
      builder.put(key, value);
    }
  }
}
