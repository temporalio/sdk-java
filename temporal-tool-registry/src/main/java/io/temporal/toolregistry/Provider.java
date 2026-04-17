package io.temporal.toolregistry;

import java.util.List;
import java.util.Map;

/**
 * Adapter interface for LLM providers. One implementation per vendor; the loop logic lives in
 * {@link ToolRegistry#runToolLoop} and {@link AgenticSession#runToolLoop}.
 *
 * <p>Adding a new provider means implementing this single method.
 */
public interface Provider {

  /**
   * Executes one turn of the conversation.
   *
   * <p>Sends the full message history plus the tool definitions to the LLM, dispatches any tool
   * calls using the registry, and returns the new messages to append plus a done flag.
   *
   * @param messages the current conversation history
   * @param tools the tools available to the model
   * @return new messages to append and a done flag
   * @throws Exception on API or dispatch errors
   */
  TurnResult runTurn(List<Map<String, Object>> messages, List<ToolDefinition> tools)
      throws Exception;
}
