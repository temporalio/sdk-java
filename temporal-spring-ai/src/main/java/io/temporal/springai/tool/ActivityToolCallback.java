package io.temporal.springai.tool;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * A wrapper for {@link ToolCallback} that indicates the underlying tool is backed by a Temporal
 * activity stub.
 *
 * <p>This wrapper delegates all operations to the underlying callback while serving as a marker to
 * indicate that tool invocations will execute as Temporal activities, providing durability,
 * automatic retries, and timeout handling.
 *
 * <p>This class is primarily used internally by {@link ActivityToolUtil} when converting activity
 * stubs to tool callbacks. Users typically don't need to create instances directly.
 *
 * @see ActivityToolUtil#fromActivityStub(Object...)
 */
public class ActivityToolCallback implements ToolCallback {
  private final ToolCallback delegate;

  /**
   * Creates a new ActivityToolCallback wrapping the given callback.
   *
   * @param delegate the underlying tool callback to wrap
   */
  public ActivityToolCallback(ToolCallback delegate) {
    this.delegate = delegate;
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
    return delegate.call(toolInput);
  }

  @Override
  public String call(String toolInput, ToolContext toolContext) {
    return delegate.call(toolInput, toolContext);
  }

  /**
   * Returns the underlying delegate callback.
   *
   * @return the wrapped callback
   */
  public ToolCallback getDelegate() {
    return delegate;
  }
}
