package io.temporal.springai.activity;

import io.temporal.springai.model.ChatModelTypes;
import io.temporal.springai.model.ChatModelTypes.Message;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
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

  private final Map<String, ChatModel> chatModels;
  private final String defaultModelName;

  /**
   * Creates an activity implementation with a single chat model.
   *
   * @param chatModel the chat model to use
   */
  public ChatModelActivityImpl(ChatModel chatModel) {
    this.chatModels = Map.of("default", chatModel);
    this.defaultModelName = "default";
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

    // Add tool callbacks (stubs that provide definitions but won't be executed
    // since internalToolExecutionEnabled is false)
    if (!CollectionUtils.isEmpty(input.tools())) {
      List<ToolCallback> toolCallbacks =
          input.tools().stream()
              .map(
                  tool ->
                      createStubToolCallback(
                          tool.function().name(),
                          tool.function().description(),
                          tool.function().jsonSchema()))
              .collect(Collectors.toList());
      optionsBuilder.toolCallbacks(toolCallbacks);
    }

    ToolCallingChatOptions chatOptions = optionsBuilder.build();

    return Prompt.builder().messages(messages).chatOptions(chatOptions).build();
  }

  private org.springframework.ai.chat.messages.Message toSpringMessage(Message message) {
    return switch (message.role()) {
      case SYSTEM -> new SystemMessage((String) message.rawContent());
      case USER -> {
        UserMessage.Builder builder = UserMessage.builder().text((String) message.rawContent());
        if (!CollectionUtils.isEmpty(message.mediaContents())) {
          builder.media(
              message.mediaContents().stream().map(this::toMedia).collect(Collectors.toList()));
        }
        yield builder.build();
      }
      case ASSISTANT ->
          AssistantMessage.builder()
              .content((String) message.rawContent())
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
                          message.toolCallId(), message.name(), (String) message.rawContent())))
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
