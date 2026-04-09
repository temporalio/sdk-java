package io.temporal.springai.util;

import io.temporal.springai.tool.ActivityToolCallback;
import io.temporal.springai.tool.ActivityToolUtil;
import io.temporal.springai.tool.DeterministicTool;
import io.temporal.springai.tool.NexusToolCallback;
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
 * <p>This class detects the type of each tool object and converts it appropriately:
 *
 * <ul>
 *   <li><b>Activity stubs</b> - Converted to {@link ActivityToolCallback} for durable execution
 *   <li><b>Local activity stubs</b> - Converted to tool callbacks for fast, local execution
 *   <li><b>Nexus service stubs</b> - Converted to {@link NexusToolCallback} for cross-namespace
 *       operations
 *   <li><b>{@link DeterministicTool} classes</b> - Converted to standard tool callbacks for direct
 *       execution
 *   <li><b>{@link SideEffectTool} classes</b> - Wrapped in {@code Workflow.sideEffect()} for
 *       recorded execution
 *   <li><b>Child workflow stubs</b> - Not supported
 * </ul>
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * WeatherActivity weatherTool = Workflow.newActivityStub(WeatherActivity.class, opts);
 * MathTools mathTools = new MathTools(); // @DeterministicTool annotated
 * TimestampTools timestamps = new TimestampTools(); // @SideEffectTool annotated
 *
 * List<ToolCallback> callbacks = TemporalToolUtil.convertTools(weatherTool, mathTools, timestamps);
 * }</pre>
 *
 * @see DeterministicTool
 * @see SideEffectTool
 * @see ActivityToolCallback
 * @see SideEffectToolCallback
 */
public final class TemporalToolUtil {

  private TemporalToolUtil() {
    // Utility class
  }

  /**
   * Converts an array of tool objects to appropriate {@link ToolCallback} instances.
   *
   * <p>Each tool object is inspected to determine its type:
   *
   * <ul>
   *   <li>Activity stubs are converted using {@link ActivityToolUtil#fromActivityStub(Object...)}
   *   <li>Local activity stubs are converted the same way (both execute as activities)
   *   <li>Nexus service stubs are converted using {@link
   *       NexusToolUtil#fromNexusServiceStub(Object...)}
   *   <li>Child workflow stubs throw {@link UnsupportedOperationException}
   *   <li>Classes annotated with {@link DeterministicTool} are converted using Spring AI's standard
   *       {@code ToolCallbacks.from(Object)}
   *   <li>Classes annotated with {@link SideEffectTool} are wrapped in {@code
   *       Workflow.sideEffect()}
   *   <li>Other objects throw {@link IllegalArgumentException}
   * </ul>
   *
   * <p>For tools that aren't properly annotated, use {@code defaultToolCallbacks()} with {@link
   * io.temporal.springai.advisor.SandboxingAdvisor} to wrap them safely at call time.
   *
   * @param toolObjects the tool objects to convert
   * @return a list of ToolCallback instances
   * @throws IllegalArgumentException if a tool object is not a recognized type
   * @throws UnsupportedOperationException if a tool type is not supported (child workflow)
   */
  public static List<ToolCallback> convertTools(Object... toolObjects) {
    List<ToolCallback> toolCallbacks = new ArrayList<>();

    for (Object toolObject : toolObjects) {
      if (toolObject == null) {
        throw new IllegalArgumentException("Tool object cannot be null");
      }

      if (TemporalStubUtil.isActivityStub(toolObject)) {
        // Activity stub - execute as durable activity
        ToolCallback[] callbacks = ActivityToolUtil.fromActivityStub(toolObject);
        toolCallbacks.addAll(List.of(callbacks));

      } else if (TemporalStubUtil.isLocalActivityStub(toolObject)) {
        // Local activity stub - execute as local activity (faster, less durable)
        ToolCallback[] callbacks = ActivityToolUtil.fromActivityStub(toolObject);
        toolCallbacks.addAll(List.of(callbacks));

      } else if (TemporalStubUtil.isNexusServiceStub(toolObject)) {
        // Nexus service stub - execute as Nexus operation
        ToolCallback[] callbacks = NexusToolUtil.fromNexusServiceStub(toolObject);
        toolCallbacks.addAll(List.of(callbacks));

      } else if (TemporalStubUtil.isChildWorkflowStub(toolObject)) {
        // Child workflow stubs are not supported
        throw new UnsupportedOperationException(
            "Child workflow stubs are not supported as tools. "
                + "Consider using an activity to wrap the child workflow call.");

      } else if (toolObject.getClass().isAnnotationPresent(DeterministicTool.class)) {
        // Deterministic tool - safe to execute directly in workflow
        toolCallbacks.addAll(List.of(ToolCallbacks.from(toolObject)));

      } else if (toolObject.getClass().isAnnotationPresent(SideEffectTool.class)) {
        // Side-effect tool - wrap in Workflow.sideEffect() for recorded execution
        ToolCallback[] rawCallbacks = ToolCallbacks.from(toolObject);
        List<ToolCallback> wrappedCallbacks =
            Arrays.stream(rawCallbacks)
                .map(SideEffectToolCallback::new)
                .map(tc -> (ToolCallback) tc)
                .toList();
        toolCallbacks.addAll(wrappedCallbacks);

      } else {
        // Unknown type - reject to prevent non-deterministic behavior
        throw new IllegalArgumentException(
            "Tool object of type '"
                + toolObject.getClass().getName()
                + "' is not a "
                + "recognized Temporal primitive (activity stub, local activity stub, Nexus service stub) or "
                + "a class annotated with @DeterministicTool or @SideEffectTool. "
                + "To use a plain object as a tool, either: "
                + "(1) annotate its class with @DeterministicTool if it's truly deterministic, "
                + "(2) annotate with @SideEffectTool if it's non-deterministic but cheap, "
                + "(3) wrap it in an activity for durable execution, or "
                + "(4) use defaultToolCallbacks() with SandboxingAdvisor to wrap unsafe tools.");
      }
    }

    return toolCallbacks;
  }

  /**
   * Checks if the given object is a recognized tool type that can be converted.
   *
   * @param toolObject the object to check
   * @return true if the object can be converted to tool callbacks
   */
  public static boolean isRecognizedToolType(Object toolObject) {
    if (toolObject == null) {
      return false;
    }
    return TemporalStubUtil.isActivityStub(toolObject)
        || TemporalStubUtil.isLocalActivityStub(toolObject)
        || TemporalStubUtil.isNexusServiceStub(toolObject)
        || toolObject.getClass().isAnnotationPresent(DeterministicTool.class)
        || toolObject.getClass().isAnnotationPresent(SideEffectTool.class);
  }
}
