package io.temporal.springai.util;

import io.temporal.springai.tool.ActivityToolUtil;
import io.temporal.springai.tool.NexusToolUtil;
import io.temporal.springai.tool.SideEffectTool;
import io.temporal.springai.tool.SideEffectToolCallback;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

/**
 * Utility class for converting tool objects to appropriate {@link ToolCallback} instances based on
 * their type.
 *
 * <p>Each tool object is detected and handled as follows:
 *
 * <ul>
 *   <li><b>Activity stubs</b> - Executed as durable Temporal activities
 *   <li><b>Local activity stubs</b> - Executed as local activities
 *   <li><b>Nexus service stubs</b> - Executed as Nexus operations
 *   <li><b>{@link SideEffectTool} classes</b> - Wrapped in {@code Workflow.sideEffect()}
 *   <li><b>Plain objects with {@code @Tool} methods</b> - Executed directly in workflow context.
 *       User is responsible for determinism.
 *   <li><b>Child workflow stubs</b> - Not supported (use a plain tool that starts a child workflow)
 * </ul>
 *
 * @see SideEffectTool
 * @see SideEffectToolCallback
 */
public final class TemporalToolUtil {

  private TemporalToolUtil() {}

  /**
   * Converts an array of tool objects to appropriate {@link ToolCallback} instances.
   *
   * @param toolObjects the tool objects to convert
   * @return a list of ToolCallback instances
   * @throws UnsupportedOperationException if a child workflow stub is passed
   */
  public static List<ToolCallback> convertTools(Object... toolObjects) {
    List<ToolCallback> toolCallbacks = new ArrayList<>();

    for (Object toolObject : toolObjects) {
      if (toolObject == null) {
        throw new IllegalArgumentException("Tool object cannot be null");
      }

      if (TemporalStubUtil.isActivityStub(toolObject)) {
        toolCallbacks.addAll(List.of(ActivityToolUtil.fromActivityStub(toolObject)));

      } else if (TemporalStubUtil.isLocalActivityStub(toolObject)) {
        toolCallbacks.addAll(List.of(ActivityToolUtil.fromActivityStub(toolObject)));

      } else if (TemporalStubUtil.isNexusServiceStub(toolObject)) {
        toolCallbacks.addAll(List.of(NexusToolUtil.fromNexusServiceStub(toolObject)));

      } else if (TemporalStubUtil.isChildWorkflowStub(toolObject)) {
        throw new UnsupportedOperationException(
            "Child workflow stubs are not supported as tools. "
                + "Use a plain tool method that starts a child workflow instead.");

      } else if (toolObject.getClass().isAnnotationPresent(SideEffectTool.class)) {
        ToolCallback[] rawCallbacks = ToolCallbacks.from(toolObject);
        toolCallbacks.addAll(
            Arrays.stream(rawCallbacks)
                .map(SideEffectToolCallback::new)
                .map(tc -> (ToolCallback) tc)
                .toList());

      } else {
        // Plain tool — executes directly in workflow context.
        // User is responsible for determinism.
        toolCallbacks.addAll(List.of(ToolCallbacks.from(toolObject)));
      }
    }

    return toolCallbacks;
  }
}
