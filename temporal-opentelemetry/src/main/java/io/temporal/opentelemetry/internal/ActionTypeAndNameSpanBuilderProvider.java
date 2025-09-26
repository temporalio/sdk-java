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
 * Default implementation of the {@link SpanBuilderProvider}.
 *
 * <p>This class creates spans with names that combine both the {@link SpanOperationType} and the
 * {@link SpanCreationContext#getActionName()}, for example
 * "StartActivity:LoadUsersFromDatabaseActivity".
 *
 * <p>This implementation also attaches relevant context information as span attributes, including:
 *
 * <ul>
 *   <li>Workflow ID
 *   <li>Run ID
 *   <li>Parent Workflow ID (for child workflows)
 *   <li>Parent Run ID (for child and continue-as-new workflows)
 * </ul>
 *
 * <p>Attribute attachment is performed defensively, handling null values gracefully to ensure spans
 * are created even when some context information is missing. This is particularly important in
 * testing scenarios or when spans are created early in workflow lifecycle.
 */
public class ActionTypeAndNameSpanBuilderProvider implements SpanBuilderProvider {
  private static final Logger logger =
      LoggerFactory.getLogger(ActionTypeAndNameSpanBuilderProvider.class);

  /** Singleton instance for use throughout the SDK. */
  public static final ActionTypeAndNameSpanBuilderProvider INSTANCE =
      new ActionTypeAndNameSpanBuilderProvider();

  /** Delimiter used to separate operation type prefix from action name in span names. */
  private static final String PREFIX_DELIMITER = ":";

  public ActionTypeAndNameSpanBuilderProvider() {}

  /**
   * Creates a new SpanBuilder for the given context.
   *
   * <p>This method generates a span name by combining the operation type and action name, then adds
   * relevant attributes from the context, handling null values gracefully.
   *
   * @param tracer the OpenTelemetry tracer to use for creating the span
   * @param context the context containing information about the operation being traced
   * @return a configured SpanBuilder ready to start a span
   */
  @Override
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
   * Generates the name of the span given the span creation context.
   *
   * <p>Combines the operation type prefix with the action name using the specified delimiter. For
   * example, "StartWorkflow:MyWorkflow" or "RunActivity:ProcessPayment".
   *
   * @param context the span creation context containing operation type and action name
   * @return the formatted span name
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
  /**
   * Creates a map of span attributes based on the span creation context.
   *
   * <p>This method selects appropriate attributes to add based on the operation type, and handles
   * null values gracefully to avoid exceptions during span creation.
   *
   * <p>Different operation types have different relevant attributes:
   *
   * <ul>
   *   <li>For workflow starts: workflow ID
   *   <li>For child workflows: workflow ID, parent workflow ID, parent run ID
   *   <li>For continue-as-new: workflow ID, parent run ID
   *   <li>For run operations: workflow ID, run ID
   * </ul>
   *
   * @param context the context containing span creation information
   * @return a map of attribute key-value pairs for the span
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
        } else if (shouldWarnAboutMissingRunId(operationType)) {
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
        // These operations don't need additional attributes
        break;

      default:
        throw new IllegalArgumentException(
            "Unknown span operation type provided: " + operationType);
    }

    return builder.build();
  }

  /**
   * Determines if a warning should be logged for a missing run ID based on operation type. Some
   * operations don't require a run ID, so we can avoid unnecessary warning logs.
   *
   * @param operationType the span operation type being processed
   * @return true if a warning should be logged, false otherwise
   */
  private boolean shouldWarnAboutMissingRunId(SpanOperationType operationType) {
    return operationType != SpanOperationType.HANDLE_QUERY;
  }

  /**
   * Helper method to add a tag to the builder only if the value is not null.
   *
   * <p>This prevents NullPointerExceptions when building attribute maps.
   *
   * @param builder the ImmutableMap.Builder to add the entry to
   * @param key the attribute key (never null)
   * @param value the attribute value (may be null, in which case nothing is added)
   */
  private void addIfNotNull(
      ImmutableMap.Builder<String, String> builder, String key, @Nullable String value) {
    if (value != null) {
      builder.put(key, value);
    }
  }
}
