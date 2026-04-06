package io.temporal.springai.tool;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Activity interface for executing tool callbacks via local activities.
 *
 * <p>This activity is used internally by {@link LocalActivityToolCallbackWrapper} to execute
 * arbitrary {@link org.springframework.ai.tool.ToolCallback}s in a deterministic manner. Since
 * callbacks cannot be serialized, they are stored in a static map and referenced by a unique ID.
 *
 * <p>This activity is automatically registered by the Spring AI plugin.
 *
 * @see LocalActivityToolCallbackWrapper
 */
@ActivityInterface
public interface ExecuteToolLocalActivity {

  /**
   * Executes a tool callback identified by the given ID.
   *
   * @param toolCallbackId the unique ID of the tool callback in the static map
   * @param toolInput the JSON input for the tool
   * @return the tool's output as a string
   */
  @ActivityMethod
  String call(String toolCallbackId, String toolInput);
}
