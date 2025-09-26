package io.temporal.opentelemetry;

import io.temporal.opentelemetry.internal.ActionTypeAndNameSpanBuilderProvider;
import java.util.HashMap;
import java.util.Map;

/** Custom span builder provider for tests that handles null workflow IDs properly. */
public class TestSpanBuilderProvider extends ActionTypeAndNameSpanBuilderProvider {

  @Override
  protected Map<String, String> getSpanTags(SpanCreationContext context) {
    try {
      // Get base tags but handle case where workflow ID might be null
      Map<String, String> baseTags = new HashMap<>();

      // Add non-null tags only
      if (context.getWorkflowId() != null) {
        baseTags.put(StandardTagNames.WORKFLOW_ID, context.getWorkflowId());
      }

      if (context.getRunId() != null) {
        baseTags.put(StandardTagNames.RUN_ID, context.getRunId());
      }

      if (context.getParentWorkflowId() != null) {
        baseTags.put(StandardTagNames.PARENT_WORKFLOW_ID, context.getParentWorkflowId());
      }

      if (context.getParentRunId() != null) {
        baseTags.put(StandardTagNames.PARENT_RUN_ID, context.getParentRunId());
      }

      return baseTags;
    } catch (Exception e) {
      // Return empty map if anything goes wrong
      return new HashMap<>();
    }
  }
}
