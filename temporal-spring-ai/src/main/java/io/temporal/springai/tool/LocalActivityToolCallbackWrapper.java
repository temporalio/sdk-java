package io.temporal.springai.tool;

import io.temporal.activity.LocalActivityOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * A wrapper that executes a {@link ToolCallback} via a local activity for deterministic replay.
 *
 * <p>This wrapper is used to make arbitrary (potentially non-deterministic) tool callbacks safe for
 * workflow execution. The actual callback execution happens in a local activity, ensuring the
 * result is recorded in workflow history.
 *
 * <p>Since {@link ToolCallback}s cannot be serialized, they are stored in a static map and
 * referenced by a unique ID. The ID is passed to the local activity, which looks up the callback
 * and executes it.
 *
 * <p><b>Memory Management:</b> Callbacks are automatically removed from the map after execution to
 * prevent memory leaks.
 *
 * <p>This class is primarily used by {@code SandboxingAdvisor} to wrap unsafe tools.
 *
 * @see ExecuteToolLocalActivity
 */
public class LocalActivityToolCallbackWrapper implements ToolCallback {

  private static final Map<String, ToolCallback> CALLBACK_REGISTRY = new ConcurrentHashMap<>();

  private final ToolCallback delegate;
  private final ExecuteToolLocalActivity stub;
  private final LocalActivityOptions options;

  /**
   * Creates a new wrapper with default local activity options.
   *
   * <p>Default options:
   *
   * <ul>
   *   <li>Start-to-close timeout: 30 seconds
   *   <li>Arguments not included in marker (for smaller history)
   * </ul>
   *
   * @param delegate the tool callback to wrap
   */
  public LocalActivityToolCallbackWrapper(ToolCallback delegate) {
    this(
        delegate,
        LocalActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(30))
            .setDoNotIncludeArgumentsIntoMarker(true)
            .build());
  }

  /**
   * Creates a new wrapper with custom local activity options.
   *
   * @param delegate the tool callback to wrap
   * @param options the local activity options to use
   */
  public LocalActivityToolCallbackWrapper(ToolCallback delegate, LocalActivityOptions options) {
    this.delegate = delegate;
    this.options = options;
    this.stub = Workflow.newLocalActivityStub(ExecuteToolLocalActivity.class, options);
  }

  @Override
  public ToolDefinition getToolDefinition() {
    return delegate.getToolDefinition();
  }

  @Override
  public ToolMetadata getToolMetadata() {
    return delegate.getToolMetadata();
  }

  @Override
  public String call(String toolInput) {
    String callbackId = UUID.randomUUID().toString();
    try {
      CALLBACK_REGISTRY.put(callbackId, delegate);
      return stub.call(callbackId, toolInput);
    } finally {
      CALLBACK_REGISTRY.remove(callbackId);
    }
  }

  @Override
  public String call(String toolInput, ToolContext toolContext) {
    // Note: ToolContext cannot be passed through the activity, so we ignore it here.
    // If context is needed, consider using activity parameters or workflow state.
    return call(toolInput);
  }

  /**
   * Returns the underlying delegate callback.
   *
   * @return the wrapped callback
   */
  public ToolCallback getDelegate() {
    return delegate;
  }

  /**
   * Looks up a callback by its ID. Used by {@link ExecuteToolLocalActivityImpl}.
   *
   * @param callbackId the callback ID
   * @return the callback, or null if not found
   */
  public static ToolCallback getCallback(String callbackId) {
    return CALLBACK_REGISTRY.get(callbackId);
  }

  /**
   * Returns the number of currently registered callbacks. Useful for testing and monitoring.
   *
   * @return the number of registered callbacks
   */
  public static int getRegisteredCallbackCount() {
    return CALLBACK_REGISTRY.size();
  }
}
