package io.temporal.opentelemetry;

import io.temporal.opentelemetry.internal.ActionTypeAndNameSpanBuilderProvider;
import java.util.HashMap;
import java.util.Map;

/**
 * A test-specific implementation of SpanBuilderProvider that handles null values safely. This can
 * be used by all tests to prevent NullPointerExceptions when creating spans.
 */
public class TestOpenTelemetrySpanBuilderProvider extends ActionTypeAndNameSpanBuilderProvider {

  @Override
  protected Map<String, String> getSpanTags(SpanCreationContext context) {
    // Use a safer approach to handle potential null values
    Map<String, String> tags = new HashMap<>();

    // Safely add workflow ID if available
    if (context.getWorkflowId() != null) {
      tags.put(StandardTagNames.WORKFLOW_ID, context.getWorkflowId());
    }

    // Add run ID if available
    if (context.getRunId() != null) {
      tags.put(StandardTagNames.RUN_ID, context.getRunId());
    }

    // Add parent workflow ID if available
    if (context.getParentWorkflowId() != null) {
      tags.put(StandardTagNames.PARENT_WORKFLOW_ID, context.getParentWorkflowId());
    }

    // Add parent run ID if available
    if (context.getParentRunId() != null) {
      tags.put(StandardTagNames.PARENT_RUN_ID, context.getParentRunId());
    }

    // Always add the resource.name attribute
    tags.put("resource.name", context.getActionName());

    return tags;
  }
}
