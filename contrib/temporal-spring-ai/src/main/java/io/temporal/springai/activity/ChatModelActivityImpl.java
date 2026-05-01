package io.temporal.springai.activity;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.springai.model.ChatModelTypes;
import io.temporal.springai.model.ChatModelTypes.Message;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.CollectionUtils;
import org.springframework.util.MimeType;

/**
 * Implementation of {@link ChatModelActivity} that delegates to a Spring AI {@link ChatModel}.
 *
 * <p>This implementation handles the conversion between Temporal-serializable types ({@link
 * ChatModelTypes}) and Spring AI types.
 *
 * <p>Supports multiple chat models. The model to use is determined by the {@code modelName} field
 * in the input. If no model name is specified, the default model is used.
 */
public class ChatModelActivityImpl implements ChatModelActivity {

  private static final Logger log = LoggerFactory.getLogger(ChatModelActivityImpl.class);

  /**
   * Reads the caller's {@link ChatOptions} back out of the serialized JSON carried on {@link
   * ChatModelTypes.ModelOptions}. Plain Jackson — the workflow side wrote the blob with a matching
   * plain {@link ObjectMapper}.
   */
  private static final ObjectMapper OPTIONS_MAPPER =
      new ObjectMapper().addMixIn(ToolCallingChatOptions.class, ToolCallingChatOptionsMixin.class);

  /**
   * Mirror of the mixin in {@code ActivityChatModel} so deserialization ignores the same tool-bag
   * properties the workflow side skipped.
   */
  @com.fasterxml.jackson.annotation.JsonIgnoreProperties(
      value = {"toolCallbacks", "toolNames", "toolContext"},
      ignoreUnknown = true)
  private abstract static class ToolCallingChatOptionsMixin {}

  private final Map<String, ChatModel> chatModels;
  private final String defaultModelName;

  /**
   * Creates an activity implementation with a single chat model.
   *
   * @param chatModel the chat model to use
   */
  public ChatModelActivityImpl(ChatModel chatModel) {
    this.chatModels = Map.of(ChatModelTypes.DEFAULT_MODEL_NAME, chatModel);
    this.defaultModelName = ChatModelTypes.DEFAULT_MODEL_NAME;
  }

  /**
   * Creates an activity implementation with multiple chat models.
   *
   * @param chatModels map of model names to chat models
   * @param defaultModelName the name of the default model to use when none is specified
   */
  public ChatModelActivityImpl(Map<String, ChatModel> chatModels, String defaultModelName) {
    this.chatModels = chatModels;
    this.defaultModelName = defaultModelName;
  }

  @Override
  public ChatModelTypes.ChatModelActivityOutput callChatModel(
      ChatModelTypes.ChatModelActivityInput input) {
    ChatModel chatModel = resolveChatModel(input.modelName());
    Prompt prompt = createPrompt(input);
    ChatResponse response = chatModel.call(prompt);
    return toOutput(response);
  }

  private ChatModel resolveChatModel(String modelName) {
    String name = (modelName != null && !modelName.isEmpty()) ? modelName : defaultModelName;
    ChatModel model = chatModels.get(name);
    if (model == null) {
      throw new IllegalArgumentException(
          "No chat model with name '" + name + "'. Available models: " + chatModels.keySet());
    }
    return model;
  }

  private Prompt createPrompt(ChatModelTypes.ChatModelActivityInput input) {
    List<org.springframework.ai.chat.messages.Message> messages =
        input.messages().stream().map(this::toSpringMessage).collect(Collectors.toList());

    List<ToolCallback> toolCallbacks = stubToolCallbacks(input);

    // Primary path: rehydrate the caller's exact ChatOptions subclass from the serialized blob.
    // Preserves provider-specific fields (OpenAI reasoning_effort, Anthropic thinking budget,
    // etc.) that aren't representable in the common ModelOptions record.
    ChatOptions rehydrated = tryRehydrateChatOptions(input.modelOptions());
    if (rehydrated instanceof ToolCallingChatOptions tcOpts) {
      tcOpts.setInternalToolExecutionEnabled(false);
      if (!toolCallbacks.isEmpty()) {
        tcOpts.setToolCallbacks(toolCallbacks);
      }
      return Prompt.builder().messages(messages).chatOptions(tcOpts).build();
    }
    if (rehydrated != null) {
      // Caller's ChatOptions isn't a ToolCallingChatOptions. Accept it as-is; tool callbacks
      // can't be attached via this path, but most provider options in practice are
      // ToolCallingChatOptions subclasses so this branch is a rare fallback.
      log.debug(
          "Rehydrated ChatOptions {} is not a ToolCallingChatOptions; tool callbacks will be"
              + " omitted for this call.",
          rehydrated.getClass().getName());
      return Prompt.builder().messages(messages).chatOptions(rehydrated).build();
    }

    // Fallback path: no serialized blob, or rehydration failed. Build a ToolCallingChatOptions
    // from the common scalar fields.
    ToolCallingChatOptions.Builder optionsBuilder =
        ToolCallingChatOptions.builder()
            .internalToolExecutionEnabled(false); // Let workflow handle tool execution

    if (input.modelOptions() != null) {
      ChatModelTypes.ModelOptions opts = input.modelOptions();
      if (opts.model() != null) optionsBuilder.model(opts.model());
      if (opts.temperature() != null) optionsBuilder.temperature(opts.temperature());
      if (opts.maxTokens() != null) optionsBuilder.maxTokens(opts.maxTokens());
      if (opts.topP() != null) optionsBuilder.topP(opts.topP());
      if (opts.topK() != null) optionsBuilder.topK(opts.topK());
      if (opts.frequencyPenalty() != null) optionsBuilder.frequencyPenalty(opts.frequencyPenalty());
      if (opts.presencePenalty() != null) optionsBuilder.presencePenalty(opts.presencePenalty());
      if (opts.stopSequences() != null) optionsBuilder.stopSequences(opts.stopSequences());
    }

    if (!toolCallbacks.isEmpty()) {
      optionsBuilder.toolCallbacks(toolCallbacks);
    }

    return Prompt.builder().messages(messages).chatOptions(optionsBuilder.build()).build();
  }

  private List<ToolCallback> stubToolCallbacks(ChatModelTypes.ChatModelActivityInput input) {
    if (CollectionUtils.isEmpty(input.tools())) {
      return List.of();
    }
    return input.tools().stream()
        .map(
            tool ->
                createStubToolCallback(
                    tool.function().name(),
                    tool.function().description(),
                    tool.function().jsonSchema()))
        .collect(Collectors.toList());
  }

  /**
   * Attempts to rehydrate the caller's exact {@link ChatOptions} subclass from the serialized blob
   * in {@code modelOptions}. Returns {@code null} if the blob is absent or rehydration fails, in
   * which case the caller should use the common-field fallback.
   */
  private ChatOptions tryRehydrateChatOptions(ChatModelTypes.ModelOptions modelOptions) {
    if (modelOptions == null
        || modelOptions.chatOptionsClass() == null
        || modelOptions.chatOptionsJson() == null) {
      return null;
    }
    String className = modelOptions.chatOptionsClass();
    try {
      Class<?> cls = Class.forName(className, true, Thread.currentThread().getContextClassLoader());
      if (!ChatOptions.class.isAssignableFrom(cls)) {
        log.warn(
            "Serialized ChatOptions class {} is not a ChatOptions; falling back to common fields.",
            className);
        return null;
      }
      return (ChatOptions) OPTIONS_MAPPER.readValue(modelOptions.chatOptionsJson(), cls);
    } catch (ClassNotFoundException e) {
      log.warn(
          "Could not load ChatOptions class {} on the activity side; falling back to common"
              + " fields. This typically means spring-ai-<provider> is not on this worker's"
              + " classpath.",
          className);
      return null;
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      log.warn(
          "Could not deserialize ChatOptions of type {} on the activity side; falling back to"
              + " common fields. Cause: {}",
          className,
          e.getMessage());
      return null;
    }
  }

  private org.springframework.ai.chat.messages.Message toSpringMessage(Message message) {
    return switch (message.role()) {
      case SYSTEM -> new SystemMessage(message.rawContent());
      case USER -> {
        UserMessage.Builder builder = UserMessage.builder().text(message.rawContent());
        if (!CollectionUtils.isEmpty(message.mediaContents())) {
          builder.media(
              message.mediaContents().stream().map(this::toMedia).collect(Collectors.toList()));
        }
        yield builder.build();
      }
      case ASSISTANT ->
          AssistantMessage.builder()
              .content(message.rawContent())
              .properties(Map.of())
              .toolCalls(
                  message.toolCalls() != null
                      ? message.toolCalls().stream()
                          .map(
                              tc ->
                                  new AssistantMessage.ToolCall(
                                      tc.id(),
                                      tc.type(),
                                      tc.function().name(),
                                      tc.function().arguments()))
                          .collect(Collectors.toList())
                      : List.of())
              .media(
                  message.mediaContents() != null
                      ? message.mediaContents().stream()
                          .map(this::toMedia)
                          .collect(Collectors.toList())
                      : List.of())
              .build();
      case TOOL ->
          ToolResponseMessage.builder()
              .responses(
                  List.of(
                      new ToolResponseMessage.ToolResponse(
                          message.toolCallId(), message.name(), message.rawContent())))
              .build();
    };
  }

  private Media toMedia(ChatModelTypes.MediaContent mediaContent) {
    MimeType mimeType = MimeType.valueOf(mediaContent.mimeType());
    if (mediaContent.uri() != null) {
      try {
        return new Media(mimeType, new URI(mediaContent.uri()));
      } catch (URISyntaxException e) {
        throw new RuntimeException("Invalid media URI: " + mediaContent.uri(), e);
      }
    } else if (mediaContent.data() != null) {
      return new Media(mimeType, new ByteArrayResource(mediaContent.data()));
    }
    throw new IllegalArgumentException("Media content must have either uri or data");
  }

  private ChatModelTypes.ChatModelActivityOutput toOutput(ChatResponse response) {
    List<ChatModelTypes.ChatModelActivityOutput.Generation> generations =
        response.getResults().stream()
            .map(
                gen ->
                    new ChatModelTypes.ChatModelActivityOutput.Generation(
                        fromAssistantMessage(gen.getOutput())))
            .collect(Collectors.toList());

    ChatModelTypes.ChatModelActivityOutput.ChatResponseMetadata metadata = null;
    if (response.getMetadata() != null) {
      var rateLimit = response.getMetadata().getRateLimit();
      var usage = response.getMetadata().getUsage();

      metadata =
          new ChatModelTypes.ChatModelActivityOutput.ChatResponseMetadata(
              response.getMetadata().getModel(),
              rateLimit != null
                  ? new ChatModelTypes.ChatModelActivityOutput.ChatResponseMetadata.RateLimit(
                      rateLimit.getRequestsLimit(),
                      rateLimit.getRequestsRemaining(),
                      rateLimit.getRequestsReset(),
                      rateLimit.getTokensLimit(),
                      rateLimit.getTokensRemaining(),
                      rateLimit.getTokensReset())
                  : null,
              usage != null
                  ? new ChatModelTypes.ChatModelActivityOutput.ChatResponseMetadata.Usage(
                      usage.getPromptTokens() != null ? usage.getPromptTokens().intValue() : null,
                      usage.getCompletionTokens() != null
                          ? usage.getCompletionTokens().intValue()
                          : null,
                      usage.getTotalTokens() != null ? usage.getTotalTokens().intValue() : null)
                  : null);
    }

    return new ChatModelTypes.ChatModelActivityOutput(generations, metadata);
  }

  private Message fromAssistantMessage(AssistantMessage assistantMessage) {
    List<Message.ToolCall> toolCalls = null;
    if (!CollectionUtils.isEmpty(assistantMessage.getToolCalls())) {
      toolCalls =
          assistantMessage.getToolCalls().stream()
              .map(
                  tc ->
                      new Message.ToolCall(
                          tc.id(),
                          tc.type(),
                          new Message.ChatCompletionFunction(tc.name(), tc.arguments())))
              .collect(Collectors.toList());
    }

    List<ChatModelTypes.MediaContent> mediaContents = null;
    if (!CollectionUtils.isEmpty(assistantMessage.getMedia())) {
      mediaContents =
          assistantMessage.getMedia().stream().map(this::fromMedia).collect(Collectors.toList());
    }

    return new Message(
        assistantMessage.getText(), Message.Role.ASSISTANT, null, null, toolCalls, mediaContents);
  }

  private ChatModelTypes.MediaContent fromMedia(Media media) {
    String mimeType = media.getMimeType().toString();
    if (media.getData() instanceof String uri) {
      return new ChatModelTypes.MediaContent(mimeType, uri);
    } else if (media.getData() instanceof byte[] data) {
      ChatModelTypes.checkMediaSize(data);
      return new ChatModelTypes.MediaContent(mimeType, data);
    }
    throw new IllegalArgumentException(
        "Unsupported media data type: " + media.getData().getClass());
  }

  /**
   * Creates a stub ToolCallback that provides a tool definition but throws if called. This is used
   * because Spring AI's ChatModel API requires ToolCallbacks, but we only need to inform the model
   * about available tools - actual execution happens in the workflow (since
   * internalToolExecutionEnabled is false).
   */
  private ToolCallback createStubToolCallback(String name, String description, String inputSchema) {
    ToolDefinition toolDefinition =
        ToolDefinition.builder()
            .name(name)
            .description(description)
            .inputSchema(inputSchema)
            .build();

    return new ToolCallback() {
      @Override
      public ToolDefinition getToolDefinition() {
        return toolDefinition;
      }

      @Override
      public String call(String toolInput) {
        throw new UnsupportedOperationException(
            "Tool execution should be handled by the workflow, not the activity. "
                + "Ensure internalToolExecutionEnabled is set to false.");
      }
    };
  }
}
