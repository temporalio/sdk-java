package io.temporal.springai.tool;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

/**
 * Implementation of {@link ExecuteToolLocalActivity} that executes tool callbacks stored in the
 * {@link LocalActivityToolCallbackWrapper#getCallback(String)} registry.
 *
 * <p>This activity is automatically registered by the Spring AI plugin.
 */
@Component
public class ExecuteToolLocalActivityImpl implements ExecuteToolLocalActivity {

  @Override
  public String call(String toolCallbackId, String toolInput) {
    ToolCallback callback = LocalActivityToolCallbackWrapper.getCallback(toolCallbackId);
    if (callback == null) {
      throw new IllegalStateException(
          "Tool callback not found for ID: "
              + toolCallbackId
              + ". "
              + "This may indicate the callback was not properly registered or was already cleaned up.");
    }
    return callback.call(toolInput);
  }
}
