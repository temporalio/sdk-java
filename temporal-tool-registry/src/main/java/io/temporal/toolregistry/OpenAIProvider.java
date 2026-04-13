package io.temporal.toolregistry;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import com.openai.models.chat.completions.ChatCompletionTool;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link Provider} implementation for the OpenAI Chat Completions API.
 *
 * <p>Messages are stored as {@code List<Map<String, Object>>} (checkpoint-safe) and converted to
 * OpenAI SDK types via Jackson JSON round-trip before each API call.
 *
 * <p>Example:
 *
 * <pre>{@code
 * OpenAIConfig cfg = OpenAIConfig.builder()
 *     .apiKey(System.getenv("OPENAI_API_KEY"))
 *     .build();
 * Provider provider = new OpenAIProvider(cfg, registry, "You are a helpful assistant.");
 * }</pre>
 */
public class OpenAIProvider implements Provider {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String DEFAULT_MODEL = "gpt-4o";

  private final OpenAIClient client;
  private final String model;
  private final String system;
  private final ToolRegistry registry;

  /**
   * Creates an OpenAIProvider.
   *
   * @param cfg provider configuration (API key, model, etc.)
   * @param registry tool registry used for dispatching tool calls
   * @param system system prompt
   */
  public OpenAIProvider(OpenAIConfig cfg, ToolRegistry registry, String system) {
    this.model = cfg.getModel() != null ? cfg.getModel() : DEFAULT_MODEL;
    this.system = system;
    this.registry = registry;
    if (cfg.getClient() != null) {
      this.client = cfg.getClient();
    } else {
      OpenAIOkHttpClient.Builder builder = OpenAIOkHttpClient.builder().apiKey(cfg.getApiKey());
      if (cfg.getBaseUrl() != null) {
        builder.baseUrl(cfg.getBaseUrl());
      }
      this.client = builder.build();
    }
  }

  /**
   * Executes one turn of the conversation.
   *
   * <p>Prepends a system message, converts the message history to OpenAI SDK types via JSON
   * round-trip, calls the Chat Completions API, dispatches any tool calls, and returns new
   * messages.
   */
  @Override
  public TurnResult runTurn(List<Map<String, Object>> messages, List<ToolDefinition> tools)
      throws Exception {
    // Build full message list: system prefix + conversation history.
    List<Map<String, Object>> full = new ArrayList<>(messages.size() + 1);
    Map<String, Object> sysMsg = new LinkedHashMap<>();
    sysMsg.put("role", "system");
    sysMsg.put("content", system);
    full.add(sysMsg);
    full.addAll(messages);

    // Convert to OpenAI ChatCompletionMessageParam via JSON round-trip.
    List<ChatCompletionMessageParam> chatMsgs = mapsToMessageParams(full);

    // Convert registry definitions to OpenAI ChatCompletionTool via JSON round-trip.
    List<ChatCompletionTool> oaiTools = registryToOpenAITools();

    ChatCompletion response =
        client
            .chat()
            .completions()
            .create(
                ChatCompletionCreateParams.builder()
                    .model(model)
                    .messages(chatMsgs)
                    .tools(oaiTools)
                    .build());

    if (response.choices().isEmpty()) {
      return new TurnResult(new ArrayList<>(), true);
    }

    ChatCompletion.Choice choice = response.choices().get(0);
    ChatCompletionMessage msg = choice.message();

    // Build the assistant message map.
    Map<String, Object> assistantMsg = new LinkedHashMap<>();
    assistantMsg.put("role", "assistant");
    assistantMsg.put("content", msg.content().orElse(null));

    // Collect only function-type tool calls (ignore custom/other variants).
    List<ChatCompletionMessageFunctionToolCall> toolCalls = new ArrayList<>();
    for (ChatCompletionMessageToolCall tc : msg.toolCalls().orElse(new ArrayList<>())) {
      if (tc.isFunction()) {
        toolCalls.add(tc.asFunction());
      }
    }

    if (!toolCalls.isEmpty()) {
      List<Map<String, Object>> callMaps = new ArrayList<>(toolCalls.size());
      for (ChatCompletionMessageFunctionToolCall tc : toolCalls) {
        Map<String, Object> callMap = new LinkedHashMap<>();
        callMap.put("id", tc.id());
        callMap.put("type", "function");
        Map<String, Object> funcMap = new LinkedHashMap<>();
        funcMap.put("name", tc.function().name());
        funcMap.put("arguments", tc.function().arguments());
        callMap.put("function", funcMap);
        callMaps.add(callMap);
      }
      assistantMsg.put("tool_calls", callMaps);
    }

    List<Map<String, Object>> newMessages = new ArrayList<>();
    newMessages.add(assistantMsg);

    ChatCompletion.Choice.FinishReason finishReason = choice.finishReason();
    boolean done =
        toolCalls.isEmpty()
            || finishReason == ChatCompletion.Choice.FinishReason.STOP
            || finishReason == ChatCompletion.Choice.FinishReason.LENGTH;
    if (done) {
      return new TurnResult(newMessages, true);
    }

    // Dispatch each tool call.
    for (ChatCompletionMessageFunctionToolCall tc : toolCalls) {
      String name = tc.function().name();
      String argsJson = tc.function().arguments();
      @SuppressWarnings("unchecked")
      Map<String, Object> input =
          argsJson != null && !argsJson.isEmpty()
              ? MAPPER.readValue(argsJson, Map.class)
              : new LinkedHashMap<>();
      String result;
      try {
        result = registry.dispatch(name, input);
      } catch (Exception e) {
        result = "error: " + e.getMessage();
      }
      Map<String, Object> toolResultMsg = new LinkedHashMap<>();
      toolResultMsg.put("role", "tool");
      toolResultMsg.put("tool_call_id", tc.id());
      toolResultMsg.put("content", result);
      newMessages.add(toolResultMsg);
    }
    return new TurnResult(newMessages, false);
  }

  // ── JSON conversion helpers ──────────────────────────────────────────────────

  private static List<ChatCompletionMessageParam> mapsToMessageParams(
      List<Map<String, Object>> messages) throws Exception {
    String json = MAPPER.writeValueAsString(messages);
    return MAPPER.readValue(json, new TypeReference<List<ChatCompletionMessageParam>>() {});
  }

  /**
   * Converts the tool registry's definitions to OpenAI ChatCompletionTool via:
   *
   * <ol>
   *   <li>ToolRegistry.toOpenAI() → List<Map> in OpenAI wire format
   *   <li>Jackson round-trip → List<ChatCompletionTool>
   * </ol>
   */
  private List<ChatCompletionTool> registryToOpenAITools() throws Exception {
    List<Map<String, Object>> openAIFormat = registry.toOpenAI();
    String json = MAPPER.writeValueAsString(openAIFormat);
    return MAPPER.readValue(json, new TypeReference<List<ChatCompletionTool>>() {});
  }
}
