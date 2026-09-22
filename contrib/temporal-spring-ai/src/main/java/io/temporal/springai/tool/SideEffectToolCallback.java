package io.temporal.springai.tool;

import io.temporal.workflow.Workflow;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * A wrapper for {@link ToolCallback} that executes the tool within {@code Workflow.sideEffect()},
 * making it safe for non-deterministic operations.
 *
 * <p>When a tool is wrapped in this callback:
 *
 * <ul>
 *   <li>The first execution records the result in workflow history
 *   <li>On replay, the recorded result is returned without re-execution
 *   <li>This ensures deterministic replay even for non-deterministic tools
 * </ul>
 *
 * <p>This is used internally when processing tools marked with {@link SideEffectTool}.
 *
 * @see SideEffectTool
 * @see io.temporal.workflow.Workflow#sideEffect(Class, io.temporal.workflow.Functions.Func)
 */
public class SideEffectToolCallback implements ToolCallback {
  private final ToolCallback delegate;

  /**
   * Creates a new SideEffectToolCallback wrapping the given callback.
   *
   * @param delegate the underlying tool callback to wrap
   */
  public SideEffectToolCallback(ToolCallback delegate) {
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
    return Workflow.sideEffect(String.class, () -> delegate.call(toolInput));
  }

  @Override
  public String call(String toolInput, ToolContext toolContext) {
    return Workflow.sideEffect(String.class, () -> delegate.call(toolInput, toolContext));
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
