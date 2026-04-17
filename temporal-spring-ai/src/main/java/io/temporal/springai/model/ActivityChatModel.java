package io.temporal.springai.model;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.springai.activity.ChatModelActivity;
import io.temporal.workflow.Workflow;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.model.tool.*;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.CollectionUtils;
import org.springframework.util.MimeType;
import reactor.core.publisher.Flux;

/**
 * A {@link ChatModel} implementation that delegates to a Temporal activity.
 *
 * <p>This class enables Spring AI chat clients to be used within Temporal workflows. AI model calls
 * are executed as activities, providing durability, automatic retries, and timeout handling.
 *
 * <p>Tool execution is handled locally in the workflow (not in the activity), allowing tools to be
 * implemented as activities, local activities, or other Temporal primitives.
 *
 * <h2>Usage</h2>
 *
 * <p>For a single chat model, use the constructor directly:
 *
 * <pre>{@code
 * @WorkflowInit
 * public MyWorkflowImpl() {
 *     ChatModelActivity chatModelActivity = Workflow.newActivityStub(
 *         ChatModelActivity.class,
 *         ActivityOptions.newBuilder()
 *             .setStartToCloseTimeout(Duration.ofMinutes(2))
 *             .build());
 *
 *     ActivityChatModel chatModel = new ActivityChatModel(chatModelActivity);
 *     this.chatClient = ChatClient.builder(chatModel).build();
 * }
 * }</pre>
 *
 * <h2>Multiple Chat Models</h2>
 *
 * <p>For applications with multiple chat models, use the static factory methods:
 *
 * <pre>{@code
 * @WorkflowInit
 * public MyWorkflowImpl() {
 *     // Use the default model (first or @Primary bean)
 *     ActivityChatModel defaultModel = ActivityChatModel.forDefault();
 *
 *     // Use a specific model by bean name
 *     ActivityChatModel openAiModel = ActivityChatModel.forModel("openAiChatModel");
 *     ActivityChatModel anthropicModel = ActivityChatModel.forModel("anthropicChatModel");
 *
 *     // Use different models for different purposes
 *     this.fastClient = TemporalChatClient.builder(openAiModel).build();
 *     this.smartClient = TemporalChatClient.builder(anthropicModel).build();
 * }
 * }</pre>
 *
 * @see #forDefault()
 * @see #forModel(String)
 */
public class ActivityChatModel implements ChatModel {

  /** Default timeout for chat model activity calls (2 minutes). */
  public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(2);

  /** Default maximum retry attempts for chat model activity calls. */
  public static final int DEFAULT_MAX_ATTEMPTS = 3;

  private final ChatModelActivity chatModelActivity;
  private final String modelName;
  private final ToolCallingManager toolCallingManager;
  private final ToolExecutionEligibilityPredicate toolExecutionEligibilityPredicate;

  /**
   * Creates a new ActivityChatModel that uses the default chat model.
   *
   * @param chatModelActivity the activity stub for calling the chat model
   */
  public ActivityChatModel(ChatModelActivity chatModelActivity) {
    this(chatModelActivity, null);
  }

  /**
   * Creates a new ActivityChatModel that uses a specific chat model.
   *
   * @param chatModelActivity the activity stub for calling the chat model
   * @param modelName the name of the chat model to use, or null for default
   */
  public ActivityChatModel(ChatModelActivity chatModelActivity, String modelName) {
    this.chatModelActivity = chatModelActivity;
    this.modelName = modelName;
    this.toolCallingManager = ToolCallingManager.builder().build();
    this.toolExecutionEligibilityPredicate = new DefaultToolExecutionEligibilityPredicate();
  }

  /**
   * Creates an ActivityChatModel for the default chat model.
   *
   * <p>This factory method creates the activity stub internally with default timeout and retry
   * options.
   *
   * <p><strong>Must be called from workflow code.</strong>
   *
   * @return an ActivityChatModel for the default chat model
   */
  public static ActivityChatModel forDefault() {
    return forModel(null, DEFAULT_TIMEOUT, DEFAULT_MAX_ATTEMPTS);
  }

  /**
   * Creates an ActivityChatModel for a specific chat model by bean name.
   *
   * <p>This factory method creates the activity stub internally with default timeout and retry
   * options.
   *
   * <p><strong>Must be called from workflow code.</strong>
   *
   * @param modelName the bean name of the chat model
   * @return an ActivityChatModel for the specified chat model
   * @throws IllegalArgumentException if no model with that name exists (at activity runtime)
   */
  public static ActivityChatModel forModel(String modelName) {
    return forModel(modelName, DEFAULT_TIMEOUT, DEFAULT_MAX_ATTEMPTS);
  }

  /**
   * Creates an ActivityChatModel for a specific chat model with custom options.
   *
   * <p><strong>Must be called from workflow code.</strong>
   *
   * @param modelName the bean name of the chat model, or null for default
   * @param timeout the activity start-to-close timeout
   * @param maxAttempts the maximum number of retry attempts
   * @return an ActivityChatModel for the specified chat model
   */
  public static ActivityChatModel forModel(String modelName, Duration timeout, int maxAttempts) {
    ChatModelActivity activity =
        Workflow.newActivityStub(
            ChatModelActivity.class,
            ActivityOptions.newBuilder()
                .setStartToCloseTimeout(timeout)
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(maxAttempts).build())
                .build());
    return new ActivityChatModel(activity, modelName);
  }

  /**
   * Returns the name of the chat model this instance uses.
   *
   * @return the model name, or null if using the default model
   */
  public String getModelName() {
    return modelName;
  }

  /**
   * Streaming is not supported through Temporal activities.
   *
   * @throws UnsupportedOperationException always
   */
  @Override
  public Flux<ChatResponse> stream(Prompt prompt) {
    throw new UnsupportedOperationException("Streaming is not supported in ActivityChatModel.");
  }

  @Override
  public ChatOptions getDefaultOptions() {
    return ToolCallingChatOptions.builder().build();
  }

  @Override
  public ChatResponse call(Prompt prompt) {
    return internalCall(prompt);
  }

  private ChatResponse internalCall(Prompt prompt) {
    // Convert prompt to activity input and call the activity
    ChatModelTypes.ChatModelActivityInput input = createActivityInput(prompt);
    ChatModelTypes.ChatModelActivityOutput output = chatModelActivity.callChatModel(input);

    // Convert activity output to ChatResponse
    ChatResponse response = toResponse(output);

    // Handle tool calls if the model requested them
    if (prompt.getOptions() != null
        && toolExecutionEligibilityPredicate.isToolExecutionRequired(
            prompt.getOptions(), response)) {
      var toolExecutionResult = toolCallingManager.executeToolCalls(prompt, response);

      if (toolExecutionResult.returnDirect()) {
        return ChatResponse.builder()
            .from(response)
            .generations(ToolExecutionResult.buildGenerations(toolExecutionResult))
            .build();
      }

      // Send tool results back to the model
      return internalCall(
          new Prompt(toolExecutionResult.conversationHistory(), prompt.getOptions()));
    }

    return response;
  }

  private ChatModelTypes.ChatModelActivityInput createActivityInput(Prompt prompt) {
    // Convert messages
    List<ChatModelTypes.Message> messages =
        prompt.getInstructions().stream()
            .flatMap(msg -> toActivityMessages(msg).stream())
            .collect(Collectors.toList());

    // Convert options
    ChatModelTypes.ModelOptions modelOptions = null;
    if (prompt.getOptions() != null) {
      ChatOptions opts = prompt.getOptions();
      modelOptions =
          new ChatModelTypes.ModelOptions(
              opts.getModel(),
              opts.getFrequencyPenalty(),
              opts.getMaxTokens(),
              opts.getPresencePenalty(),
              opts.getStopSequences(),
              opts.getTemperature(),
              opts.getTopK(),
              opts.getTopP());
    }

    // Convert tool definitions
    List<ChatModelTypes.FunctionTool> tools = List.of();
    if (prompt.getOptions() instanceof ToolCallingChatOptions toolOptions) {
      List<ToolDefinition> toolDefinitions = toolCallingManager.resolveToolDefinitions(toolOptions);
      if (!CollectionUtils.isEmpty(toolDefinitions)) {
        tools =
            toolDefinitions.stream()
                .map(
                    td ->
                        new ChatModelTypes.FunctionTool(
                            new ChatModelTypes.FunctionTool.Function(
                                td.name(), td.description(), td.inputSchema())))
                .collect(Collectors.toList());
      }
    }

    return new ChatModelTypes.ChatModelActivityInput(modelName, messages, modelOptions, tools);
  }

  private List<ChatModelTypes.Message> toActivityMessages(Message message) {
    return switch (message.getMessageType()) {
      case SYSTEM ->
          List.of(
              new ChatModelTypes.Message(message.getText(), ChatModelTypes.Message.Role.SYSTEM));
      case USER -> {
        List<ChatModelTypes.MediaContent> mediaContents = null;
        if (message instanceof UserMessage userMessage
            && !CollectionUtils.isEmpty(userMessage.getMedia())) {
          mediaContents =
              userMessage.getMedia().stream()
                  .map(this::toMediaContent)
                  .collect(Collectors.toList());
        }
        yield List.of(
            new ChatModelTypes.Message(
                message.getText(), mediaContents, ChatModelTypes.Message.Role.USER));
      }
      case ASSISTANT -> {
        AssistantMessage assistantMessage = (AssistantMessage) message;
        List<ChatModelTypes.Message.ToolCall> toolCalls = null;
        if (!CollectionUtils.isEmpty(assistantMessage.getToolCalls())) {
          toolCalls =
              assistantMessage.getToolCalls().stream()
                  .map(
                      tc ->
                          new ChatModelTypes.Message.ToolCall(
                              tc.id(),
                              tc.type(),
                              new ChatModelTypes.Message.ChatCompletionFunction(
                                  tc.name(), tc.arguments())))
                  .collect(Collectors.toList());
        }
        List<ChatModelTypes.MediaContent> mediaContents = null;
        if (!CollectionUtils.isEmpty(assistantMessage.getMedia())) {
          mediaContents =
              assistantMessage.getMedia().stream()
                  .map(this::toMediaContent)
                  .collect(Collectors.toList());
        }
        yield List.of(
            new ChatModelTypes.Message(
                assistantMessage.getText(),
                ChatModelTypes.Message.Role.ASSISTANT,
                null,
                null,
                toolCalls,
                mediaContents));
      }
      case TOOL -> {
        ToolResponseMessage toolMessage = (ToolResponseMessage) message;
        yield toolMessage.getResponses().stream()
            .map(
                tr ->
                    new ChatModelTypes.Message(
                        tr.responseData(),
                        ChatModelTypes.Message.Role.TOOL,
                        tr.name(),
                        tr.id(),
                        null,
                        null))
            .collect(Collectors.toList());
      }
    };
  }

  private ChatModelTypes.MediaContent toMediaContent(Media media) {
    String mimeType = media.getMimeType().toString();
    if (media.getData() instanceof String uri) {
      return new ChatModelTypes.MediaContent(mimeType, uri);
    } else if (media.getData() instanceof byte[] data) {
      return new ChatModelTypes.MediaContent(mimeType, data);
    }
    throw new IllegalArgumentException(
        "Unsupported media data type: " + media.getData().getClass());
  }

  private ChatResponse toResponse(ChatModelTypes.ChatModelActivityOutput output) {
    List<Generation> generations =
        output.generations().stream()
            .map(gen -> new Generation(toAssistantMessage(gen.message())))
            .collect(Collectors.toList());

    var builder = ChatResponse.builder().generations(generations);
    if (output.metadata() != null) {
      builder.metadata(ChatResponseMetadata.builder().model(output.metadata().model()).build());
    }
    return builder.build();
  }

  private AssistantMessage toAssistantMessage(ChatModelTypes.Message message) {
    List<AssistantMessage.ToolCall> toolCalls = List.of();
    if (!CollectionUtils.isEmpty(message.toolCalls())) {
      toolCalls =
          message.toolCalls().stream()
              .map(
                  tc ->
                      new AssistantMessage.ToolCall(
                          tc.id(), tc.type(), tc.function().name(), tc.function().arguments()))
              .collect(Collectors.toList());
    }

    List<Media> media = List.of();
    if (!CollectionUtils.isEmpty(message.mediaContents())) {
      media = message.mediaContents().stream().map(this::toMedia).collect(Collectors.toList());
    }

    return AssistantMessage.builder()
        .content(message.rawContent())
        .properties(Map.of())
        .toolCalls(toolCalls)
        .media(media)
        .build();
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
}
