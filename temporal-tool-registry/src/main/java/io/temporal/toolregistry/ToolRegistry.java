package io.temporal.toolregistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps tool names to definitions and handlers.
 *
 * <p>Tools are registered in Anthropic's {@code tool_use} JSON format. The registry exports them
 * for Anthropic or OpenAI and dispatches incoming tool calls to the appropriate handler.
 *
 * <p>A ToolRegistry is not safe for concurrent modification; build it before passing it to
 * concurrent activities.
 *
 * <p>Example:
 *
 * <pre>{@code
 * ToolRegistry registry = new ToolRegistry();
 * registry.register(
 *     ToolDefinition.builder()
 *         .name("flag_issue")
 *         .description("Flag a problem")
 *         .inputSchema(Map.of("type", "object", "properties",
 *             Map.of("description", Map.of("type", "string")),
 *             "required", List.of("description")))
 *         .build(),
 *     input -> {
 *         issues.add((String) input.get("description"));
 *         return "recorded";
 *     });
 * }</pre>
 */
public class ToolRegistry {

  private final List<ToolDefinition> defs = new ArrayList<>();
  private final Map<String, ToolHandler> handlers = new HashMap<>();

  /** Registers a tool definition and its handler. */
  public void register(ToolDefinition definition, ToolHandler handler) {
    defs.add(definition);
    handlers.put(definition.getName(), handler);
  }

  /**
   * Dispatches a tool call to the registered handler.
   *
   * @throws IllegalArgumentException if no handler is registered for {@code name}
   */
  public String dispatch(String name, Map<String, Object> input) throws Exception {
    ToolHandler handler = handlers.get(name);
    if (handler == null) {
      throw new IllegalArgumentException("toolregistry: unknown tool \"" + name + "\"");
    }
    return handler.handle(input);
  }

  /** Returns a snapshot of the registered tool definitions. */
  public List<ToolDefinition> definitions() {
    return Collections.unmodifiableList(new ArrayList<>(defs));
  }

  /**
   * Returns the tool definitions in Anthropic tool_use format.
   *
   * <pre>{@code
   * [{"name": "...", "description": "...", "input_schema": {...}}]
   * }</pre>
   */
  public List<Map<String, Object>> toAnthropic() {
    List<Map<String, Object>> result = new ArrayList<>(defs.size());
    for (ToolDefinition def : defs) {
      Map<String, Object> tool = new LinkedHashMap<>();
      tool.put("name", def.getName());
      tool.put("description", def.getDescription());
      tool.put("input_schema", def.getInputSchema());
      result.add(tool);
    }
    return result;
  }

  /**
   * Returns the tool definitions in OpenAI function-calling format.
   *
   * <pre>{@code
   * [{"type": "function", "function": {"name": "...", "description": "...", "parameters": {...}}}]
   * }</pre>
   */
  public List<Map<String, Object>> toOpenAI() {
    List<Map<String, Object>> result = new ArrayList<>(defs.size());
    for (ToolDefinition def : defs) {
      Map<String, Object> function = new LinkedHashMap<>();
      function.put("name", def.getName());
      function.put("description", def.getDescription());
      function.put("parameters", def.getInputSchema());

      Map<String, Object> tool = new LinkedHashMap<>();
      tool.put("type", "function");
      tool.put("function", function);
      result.add(tool);
    }
    return result;
  }

  /**
   * Runs a complete multi-turn LLM tool-calling loop to completion.
   *
   * <p>This is the primary entry point for simple, non-resumable loops. For crash-safe sessions
   * with heartbeat checkpointing, use {@link AgenticSession#runWithSession}.
   *
   * @param provider the LLM provider adapter
   * @param registry the tool registry (may be the same object, provided for clarity)
   * @param system the system prompt
   * @param prompt the initial user prompt
   * @return the full message history on completion
   */
  public static List<Map<String, Object>> runToolLoop(
      Provider provider, ToolRegistry registry, String system, String prompt) throws Exception {
    List<Map<String, Object>> messages = new ArrayList<>();
    Map<String, Object> userMsg = new LinkedHashMap<>();
    userMsg.put("role", "user");
    userMsg.put("content", prompt);
    messages.add(userMsg);

    while (true) {
      TurnResult result = provider.runTurn(messages, registry.definitions());
      messages.addAll(result.getNewMessages());
      if (result.isDone()) {
        return Collections.unmodifiableList(messages);
      }
    }
  }
}
