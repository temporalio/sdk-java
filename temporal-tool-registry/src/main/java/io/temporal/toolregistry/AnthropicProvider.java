package io.temporal.toolregistry;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolUnion;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * {@link Provider} implementation for the Anthropic Messages API.
 *
 * <p>Messages are stored as {@code List<Map<String, Object>>} (checkpoint-safe) and converted to
 * Anthropic SDK types via Jackson JSON round-trip before each API call.
 *
 * <p>Example:
 *
 * <pre>{@code
 * AnthropicConfig cfg = AnthropicConfig.builder()
 *     .apiKey(System.getenv("ANTHROPIC_API_KEY"))
 *     .build();
 * Provider provider = new AnthropicProvider(cfg, registry, "You are a helpful assistant.");
 * }</pre>
 */
public class AnthropicProvider implements Provider {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String DEFAULT_MODEL = "claude-sonnet-4-6";

  private final AnthropicClient client;
  private final String model;
  private final String system;
  private final ToolRegistry registry;

  /**
   * Creates an AnthropicProvider.
   *
   * @param cfg provider configuration (API key, model, etc.)
   * @param registry tool registry used for dispatching tool calls
   * @param system system prompt
   */
  public AnthropicProvider(AnthropicConfig cfg, ToolRegistry registry, String system) {
    this.model = cfg.getModel() != null ? cfg.getModel() : DEFAULT_MODEL;
    this.system = system;
    this.registry = registry;
    if (cfg.getClient() != null) {
      this.client = cfg.getClient();
    } else {
      AnthropicOkHttpClient.Builder builder =
          AnthropicOkHttpClient.builder().apiKey(cfg.getApiKey());
      if (cfg.getBaseUrl() != null) {
        builder.baseUrl(cfg.getBaseUrl());
      }
      this.client = builder.build();
    }
  }

  /**
   * Executes one turn of the conversation.
   *
   * <p>Converts the message history and tool definitions to Anthropic SDK types via JSON
   * round-trip, calls the Messages API, dispatches any tool calls, and returns the new messages.
   */
  @Override
  public TurnResult runTurn(List<Map<String, Object>> messages, List<ToolDefinition> tools)
      throws Exception {
    // Convert message maps to Anthropic MessageParam via JSON round-trip.
    List<MessageParam> msgParams = mapsToMessageParams(messages);

    // Convert ToolDefinition list to Anthropic ToolUnion list via JSON round-trip.
    List<ToolUnion> toolUnions = toolDefsToToolUnions(tools);

    Message response =
        client
            .messages()
            .create(
                MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(4096L)
                    .system(system)
                    .messages(msgParams)
                    .tools(toolUnions)
                    .build());

    // Convert response content blocks to plain maps for checkpoint-safe storage.
    List<Map<String, Object>> contentMaps = contentBlocksToMaps(response);

    List<Map<String, Object>> newMessages = new ArrayList<>();
    Map<String, Object> assistantMsg = new LinkedHashMap<>();
    assistantMsg.put("role", "assistant");
    assistantMsg.put("content", contentMaps);
    newMessages.add(assistantMsg);

    // Collect tool-use blocks.
    List<Map<String, Object>> toolCalls =
        contentMaps.stream()
            .filter(b -> "tool_use".equals(b.get("type")))
            .collect(Collectors.toList());

    boolean endTurn = response.stopReason().map(r -> r == StopReason.END_TURN).orElse(false);
    if (toolCalls.isEmpty() || endTurn) {
      return new TurnResult(newMessages, true);
    }

    // Dispatch each tool call and collect results.
    List<Map<String, Object>> toolResults = new ArrayList<>(toolCalls.size());
    for (Map<String, Object> call : toolCalls) {
      String name = (String) call.get("name");
      String id = (String) call.get("id");
      @SuppressWarnings("unchecked")
      Map<String, Object> input = (Map<String, Object>) call.get("input");
      String result;
      boolean isError = false;
      try {
        result = registry.dispatch(name, input);
      } catch (Exception e) {
        result = "error: " + e.getMessage();
        isError = true;
      }
      Map<String, Object> toolResult = new LinkedHashMap<>();
      toolResult.put("type", "tool_result");
      toolResult.put("tool_use_id", id);
      toolResult.put("content", result);
      if (isError) {
        toolResult.put("is_error", Boolean.TRUE);
      }
      toolResults.add(toolResult);
    }
    Map<String, Object> toolResultMsg = new LinkedHashMap<>();
    toolResultMsg.put("role", "user");
    toolResultMsg.put("content", toolResults);
    newMessages.add(toolResultMsg);
    return new TurnResult(newMessages, false);
  }

  // ── JSON conversion helpers ──────────────────────────────────────────────────

  private static List<MessageParam> mapsToMessageParams(List<Map<String, Object>> messages)
      throws Exception {
    String json = MAPPER.writeValueAsString(messages);
    return MAPPER.readValue(json, new TypeReference<List<MessageParam>>() {});
  }

  private static List<ToolUnion> toolDefsToToolUnions(List<ToolDefinition> defs) throws Exception {
    String json = MAPPER.writeValueAsString(defs);
    List<Tool> tools = MAPPER.readValue(json, new TypeReference<List<Tool>>() {});
    return tools.stream().map(ToolUnion::ofTool).collect(Collectors.toList());
  }

  private static List<Map<String, Object>> contentBlocksToMaps(Message response) throws Exception {
    String json = MAPPER.writeValueAsString(response.content());
    return MAPPER.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
  }
}
