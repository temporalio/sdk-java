package io.temporal.springai.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import java.util.List;

/**
 * Serializable types for chat model activity requests and responses.
 *
 * <p>These records are designed to be serialized by Temporal's data converter and passed between
 * workflows and activities.
 */
public final class ChatModelTypes {

  /**
   * The name used for the default chat model when no {@code modelName} is specified on an activity
   * input or when {@link io.temporal.springai.model.ActivityChatModel#forDefault()} is called.
   * Lives here rather than on {@code SpringAiPlugin} so both the activity impl and the plugin can
   * reference it without the activity package importing the plugin package.
   */
  public static final String DEFAULT_MODEL_NAME = "default";

  private ChatModelTypes() {}

  /**
   * Input to the chat model activity.
   *
   * @param modelName the name of the chat model bean to use (null for default)
   * @param messages the conversation messages
   * @param modelOptions options for the chat model (temperature, max tokens, etc.)
   * @param tools tool definitions the model may call
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ChatModelActivityInput(
      @JsonProperty("model_name") String modelName,
      @JsonProperty("messages") List<Message> messages,
      @JsonProperty("model_options") ModelOptions modelOptions,
      @JsonProperty("tools") List<FunctionTool> tools) {
    /** Creates input for the default chat model. */
    public ChatModelActivityInput(
        List<Message> messages, ModelOptions modelOptions, List<FunctionTool> tools) {
      this(null, messages, modelOptions, tools);
    }
  }

  /**
   * Output from the chat model activity.
   *
   * @param generations the generated responses
   * @param metadata response metadata (model, usage, rate limits)
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ChatModelActivityOutput(
      @JsonProperty("generations") List<Generation> generations,
      @JsonProperty("metadata") ChatResponseMetadata metadata) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Generation(@JsonProperty("message") Message message) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChatResponseMetadata(
        @JsonProperty("model") String model,
        @JsonProperty("rate_limit") RateLimit rateLimit,
        @JsonProperty("usage") Usage usage) {
      @JsonInclude(JsonInclude.Include.NON_NULL)
      @JsonIgnoreProperties(ignoreUnknown = true)
      public record RateLimit(
          @JsonProperty("request_limit") Long requestLimit,
          @JsonProperty("request_remaining") Long requestRemaining,
          @JsonProperty("request_reset") Duration requestReset,
          @JsonProperty("token_limit") Long tokenLimit,
          @JsonProperty("token_remaining") Long tokenRemaining,
          @JsonProperty("token_reset") Duration tokenReset) {}

      @JsonInclude(JsonInclude.Include.NON_NULL)
      @JsonIgnoreProperties(ignoreUnknown = true)
      public record Usage(
          @JsonProperty("prompt_tokens") Integer promptTokens,
          @JsonProperty("completion_tokens") Integer completionTokens,
          @JsonProperty("total_tokens") Integer totalTokens) {}
    }
  }

  /**
   * A message in the conversation.
   *
   * @param rawContent the message text content
   * @param role the role of the message author
   * @param name optional name for the participant
   * @param toolCallId tool call ID this message responds to (for TOOL role)
   * @param toolCalls tool calls requested by the model (for ASSISTANT role)
   * @param mediaContents optional media attachments
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Message(
      @JsonProperty("content") String rawContent,
      @JsonProperty("role") Role role,
      @JsonProperty("name") String name,
      @JsonProperty("tool_call_id") String toolCallId,
      @JsonProperty("tool_calls")
          @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
          List<ToolCall> toolCalls,
      @JsonProperty("media") @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
          List<MediaContent> mediaContents) {
    public Message(String content, Role role) {
      this(content, role, null, null, null, null);
    }

    public Message(String content, List<MediaContent> mediaContents, Role role) {
      this(content, role, null, null, null, mediaContents);
    }

    public enum Role {
      @JsonProperty("system")
      SYSTEM,
      @JsonProperty("user")
      USER,
      @JsonProperty("assistant")
      ASSISTANT,
      @JsonProperty("tool")
      TOOL
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ToolCall(
        @JsonProperty("index") Integer index,
        @JsonProperty("id") String id,
        @JsonProperty("type") String type,
        @JsonProperty("function") ChatCompletionFunction function) {
      public ToolCall(String id, String type, ChatCompletionFunction function) {
        this(null, id, type, function);
      }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChatCompletionFunction(
        @JsonProperty("name") String name, @JsonProperty("arguments") String arguments) {}
  }

  /**
   * Media content within a message.
   *
   * @param mimeType the MIME type (e.g., "image/png")
   * @param uri optional URI to the content
   * @param data optional raw data bytes
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record MediaContent(
      @JsonProperty("mime_type") String mimeType,
      @JsonProperty("uri") String uri,
      @JsonProperty("data") byte[] data) {
    public MediaContent(String mimeType, String uri) {
      this(mimeType, uri, null);
    }

    public MediaContent(String mimeType, byte[] data) {
      this(mimeType, null, data);
    }
  }

  /** A tool the model may call. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record FunctionTool(
      @JsonProperty("type") String type, @JsonProperty("function") Function function) {
    public FunctionTool(Function function) {
      this("function", function);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Function(
        @JsonProperty("name") String name,
        @JsonProperty("description") String description,
        @JsonProperty("json_schema") String jsonSchema) {}
  }

  /** Model options for the chat request. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ModelOptions(
      @JsonProperty("model") String model,
      @JsonProperty("frequency_penalty") Double frequencyPenalty,
      @JsonProperty("max_tokens") Integer maxTokens,
      @JsonProperty("presence_penalty") Double presencePenalty,
      @JsonProperty("stop_sequences") List<String> stopSequences,
      @JsonProperty("temperature") Double temperature,
      @JsonProperty("top_k") Integer topK,
      @JsonProperty("top_p") Double topP) {}
}
