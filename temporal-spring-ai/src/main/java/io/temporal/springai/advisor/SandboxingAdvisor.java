package io.temporal.springai.advisor;

import io.temporal.springai.tool.ActivityToolCallback;
import io.temporal.springai.tool.LocalActivityToolCallbackWrapper;
import io.temporal.springai.tool.NexusToolCallback;
import io.temporal.springai.tool.SideEffectToolCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/**
 * An advisor that automatically wraps unsafe tool callbacks in local activities.
 *
 * <p>This advisor inspects all tool callbacks in a chat request and ensures they are safe for
 * workflow execution:
 *
 * <ul>
 *   <li>{@link ActivityToolCallback} - Already safe (executes as activity)
 *   <li>{@link NexusToolCallback} - Already safe (executes as Nexus operation)
 *   <li>{@link SideEffectToolCallback} - Already safe (wrapped in sideEffect())
 *   <li>Other callbacks - Wrapped in {@link LocalActivityToolCallbackWrapper} with warning
 * </ul>
 *
 * <p>This provides a safety net for users who pass arbitrary Spring AI tools that may not be
 * workflow-safe. A warning is logged for each wrapped tool to help users understand how to properly
 * annotate their tools.
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * this.chatClient = TemporalChatClient.builder(activityChatModel)
 *         .defaultAdvisors(new SandboxingAdvisor())
 *         .defaultTools(new UnsafeTools())  // Will be wrapped with warning
 *         .build();
 * }</pre>
 *
 * <h2>When to Use</h2>
 *
 * <ul>
 *   <li>Development and prototyping
 *   <li>Migrating existing Spring AI code to Temporal
 *   <li>Third-party tools you can't annotate
 * </ul>
 *
 * <h2>Performance Considerations</h2>
 *
 * <p>Wrapping tools in local activities adds overhead compared to properly annotated tools. For
 * production, annotate your tools with {@code @DeterministicTool} or {@code @SideEffectTool}, or
 * use activity stubs.
 *
 * @see io.temporal.springai.tool.DeterministicTool
 * @see io.temporal.springai.tool.SideEffectTool
 * @see LocalActivityToolCallbackWrapper
 */
public class SandboxingAdvisor implements CallAdvisor {

  private static final Logger logger = LoggerFactory.getLogger(SandboxingAdvisor.class);

  @Override
  public ChatClientResponse adviseCall(
      ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
    var prompt = chatClientRequest.prompt();

    if (prompt.getOptions() instanceof ToolCallingChatOptions toolCallingChatOptions) {
      var toolCallbacks = toolCallingChatOptions.getToolCallbacks();

      if (toolCallbacks != null && !toolCallbacks.isEmpty()) {
        var wrappedCallbacks =
            toolCallbacks.stream()
                .map(
                    tc -> {
                      if (tc instanceof ActivityToolCallback
                          || tc instanceof NexusToolCallback
                          || tc instanceof SideEffectToolCallback) {
                        // Already safe for workflow execution
                        return tc;
                      } else if (tc instanceof LocalActivityToolCallbackWrapper) {
                        // Already wrapped
                        return tc;
                      } else {
                        // Wrap in local activity for safety
                        String toolName =
                            tc.getToolDefinition() != null
                                ? tc.getToolDefinition().name()
                                : tc.getClass().getSimpleName();
                        logger.warn(
                            "Tool '{}' ({}) is not guaranteed to be deterministic. "
                                + "Wrapping in local activity for workflow safety. "
                                + "Consider using @DeterministicTool, @SideEffectTool, or an activity stub.",
                            toolName,
                            tc.getClass().getName());
                        return new LocalActivityToolCallbackWrapper(tc);
                      }
                    })
                .toList();

        toolCallingChatOptions.setToolCallbacks(wrappedCallbacks);
      }
    }

    return callAdvisorChain.nextCall(chatClientRequest);
  }

  @Override
  public String getName() {
    return this.getClass().getSimpleName();
  }

  @Override
  public int getOrder() {
    // Run early to wrap tools before other advisors see them
    return Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER;
  }
}
