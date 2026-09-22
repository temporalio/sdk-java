package io.temporal.springai.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.springai.activity.ChatModelActivity;
import io.temporal.springai.plugin.SpringAiPlugin;
import io.temporal.springai.plugin.SpringAiPluginOptions;
import io.temporal.workflow.Workflow;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.RateLimit;
import org.springframework.ai.chat.metadata.Usage;
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
 * <p>Build instances via the static factory methods:
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

  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(ActivityChatModel.class);

  /**
   * Used to serialize the caller's {@link ChatOptions} into a string so the activity side can
   * rehydrate the exact subclass. Plain Jackson with no Temporal-specific configuration — the
   * output goes into a {@code String} field of {@link ChatModelTypes.ModelOptions}, which
   * Temporal's own data converter then handles as normal.
   */
  private static final ObjectMapper OPTIONS_MAPPER =
      new ObjectMapper().addMixIn(ToolCallingChatOptions.class, ToolCallingChatOptionsMixin.class);

  /**
   * Jackson mixin that skips {@link ToolCallingChatOptions}'s tool-callback bag on serialization.
   * Tool definitions cross the activity boundary via {@link ChatModelTypes.FunctionTool} — the
   * actual callbacks are re-stubbed on the activity side — so we don't need to ship them, and their
   * concrete implementations (method tool callbacks, activity proxies, etc.) are not
   * Jackson-friendly.
   */
  @com.fasterxml.jackson.annotation.JsonIgnoreProperties({
    "toolCallbacks",
    "toolNames",
    "toolContext"
  })
  private abstract static class ToolCallingChatOptionsMixin {}

  /** Default timeout for chat model activity calls (2 minutes). */
  public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(2);

  /** Default maximum retry attempts for chat model activity calls. */
  public static final int DEFAULT_MAX_ATTEMPTS = 3;

  /**
   * Error types that the default retry policy treats as non-retryable. These represent clearly
   * permanent failures — a bad API key, an invalid prompt, an unknown model name — where retrying
   * wastes time and money.
   *
   * <p>Applied only to the factories that build {@link ActivityOptions} internally. When callers
   * pass their own {@link ActivityOptions} (via {@link #forDefault(ActivityOptions)} or {@link
   * #forModel(String, ActivityOptions)}), their {@link RetryOptions} are used verbatim.
   */
  public static final List<String> DEFAULT_NON_RETRYABLE_ERROR_TYPES =
      List.of(
          "org.springframework.ai.retry.NonTransientAiException",
          "java.lang.IllegalArgumentException");

  private final ChatModelActivity chatModelActivity;
  @Nullable private final String modelName;
  private final ActivityOptions baseOptions;
  private final ToolCallingManager toolCallingManager;
  private final ToolExecutionEligibilityPredicate toolExecutionEligibilityPredicate;

  /** Use one of the {@link #forDefault()} / {@link #forModel(String)} factories. */
  private ActivityChatModel(
      ChatModelActivity chatModelActivity,
      @Nullable String modelName,
      ActivityOptions baseOptions) {
    this.chatModelActivity = chatModelActivity;
    this.modelName = modelName;
    this.baseOptions = baseOptions;
    this.toolCallingManager = ToolCallingManager.builder().build();
    this.toolExecutionEligibilityPredicate = new DefaultToolExecutionEligibilityPredicate();
  }

  /**
   * Creates an ActivityChatModel for the default chat model.
   *
   * <p>Options resolution order:
   *
   * <ol>
   *   <li>An entry registered on {@link SpringAiPlugin} under {@link
   *       ChatModelTypes#DEFAULT_MODEL_NAME} in the per-model {@code ActivityOptions} map, if any.
   *   <li>The plugin's default {@link ActivityOptions} (2-minute start-to-close, 3 attempts,
   *       clearly permanent AI errors marked non-retryable).
   * </ol>
   *
   * <p>Callers who want to set explicit options should use {@link #forDefault(ActivityOptions)} —
   * explicit options bypass the registry entirely.
   *
   * <p><strong>Must be called from workflow code.</strong>
   *
   * @return an ActivityChatModel for the default chat model
   */
  public static ActivityChatModel forDefault() {
    ActivityOptions options =
        SpringAiPluginOptions.optionsFor(ChatModelTypes.DEFAULT_MODEL_NAME)
            .orElseGet(ActivityChatModel::defaultActivityOptions);
    return forDefault(options);
  }

  /**
   * Creates an ActivityChatModel for the default chat model using the supplied {@link
   * ActivityOptions}. Pass this when you need to customize any field on the chat activity stub —
   * timeouts, retry policy, task queue, heartbeat, priority, etc. Build on top of {@link
   * #defaultActivityOptions()} to inherit the plugin's non-retryable-AI-error classification.
   *
   * <p><strong>Must be called from workflow code.</strong>
   *
   * @param options the activity options to use for each chat call
   * @return an ActivityChatModel for the default chat model
   */
  public static ActivityChatModel forDefault(ActivityOptions options) {
    return forModel(null, options);
  }

  /**
   * Creates an ActivityChatModel for a specific chat model by bean name.
   *
   * <p>Options resolution order:
   *
   * <ol>
   *   <li>An entry registered on {@link SpringAiPlugin} under {@code modelName} in the per-model
   *       {@code ActivityOptions} map, if any.
   *   <li>An entry registered under {@link ChatModelTypes#DEFAULT_MODEL_NAME} in the per-model map,
   *       which acts as a user-declared catch-all for models without a specific entry.
   *   <li>The plugin's default {@link ActivityOptions} (2-minute start-to-close, 3 attempts,
   *       clearly permanent AI errors marked non-retryable).
   * </ol>
   *
   * <p>Callers who want to set explicit options should use {@link #forModel(String,
   * ActivityOptions)} — explicit options bypass the registry entirely.
   *
   * <p><strong>Must be called from workflow code.</strong>
   *
   * @param modelName the bean name of the chat model
   * @return an ActivityChatModel for the specified chat model
   * @throws IllegalArgumentException if no model with that name exists (at activity runtime)
   */
  public static ActivityChatModel forModel(String modelName) {
    ActivityOptions options =
        SpringAiPluginOptions.optionsFor(modelName)
            .or(() -> SpringAiPluginOptions.optionsFor(ChatModelTypes.DEFAULT_MODEL_NAME))
            .orElseGet(ActivityChatModel::defaultActivityOptions);
    return forModel(modelName, options);
  }

  /**
   * Creates an ActivityChatModel for a specific chat model using the supplied {@link
   * ActivityOptions}. The provided options are used verbatim — the plugin does not augment the
   * caller's {@link RetryOptions} or merge in its defaults. If you want the plugin-default
   * non-retryable error classification, copy {@link #DEFAULT_NON_RETRYABLE_ERROR_TYPES} into your
   * own {@link RetryOptions}.
   *
   * <p><strong>Must be called from workflow code.</strong>
   *
   * @param modelName the bean name of the chat model, or null for default
   * @param options the activity options to use for each chat call
   * @return an ActivityChatModel for the specified chat model
   */
  public static ActivityChatModel forModel(@Nullable String modelName, ActivityOptions options) {
    ChatModelActivity activity = Workflow.newActivityStub(ChatModelActivity.class, options);
    return new ActivityChatModel(activity, modelName, options);
  }

  /**
   * Returns the plugin's default {@link ActivityOptions} for chat model calls. Useful as a starting
   * point when you want to customize one or two fields without losing the sensible defaults:
   *
   * <pre>{@code
   * ActivityChatModel.forDefault(
   *     ActivityOptions.newBuilder(ActivityChatModel.defaultActivityOptions())
   *         .setTaskQueue("chat-heavy")
   *         .build());
   * }</pre>
   */
  public static ActivityOptions defaultActivityOptions() {
    return defaultActivityOptions(DEFAULT_TIMEOUT, DEFAULT_MAX_ATTEMPTS);
  }

  private static ActivityOptions defaultActivityOptions(Duration timeout, int maxAttempts) {
    return ActivityOptions.newBuilder()
        .setStartToCloseTimeout(timeout)
        .setRetryOptions(
            RetryOptions.newBuilder()
                .setMaximumAttempts(maxAttempts)
                .setDoNotRetry(DEFAULT_NON_RETRYABLE_ERROR_TYPES.toArray(new String[0]))
                .build())
        .build();
  }

  /**
   * Returns the name of the chat model this instance uses, or null if it uses the plugin default
   * (the {@code @Primary} {@code ChatModel} bean or the first one registered).
   */
  @Nullable
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
    ChatModelActivity stub = stubForCall(prompt);
    ChatModelTypes.ChatModelActivityOutput output = stub.callChatModel(input);

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

  private ChatModelActivity stubForCall(Prompt prompt) {
    ActivityOptions withSummary =
        ActivityOptions.newBuilder(baseOptions).setSummary(buildSummary()).build();
    return Workflow.newActivityStub(ChatModelActivity.class, withSummary);
  }

  /**
   * Builds the activity Summary. Intentionally omits the user prompt — including even a truncated
   * slice would leak whatever the prompt contains (PII, secrets, internal identifiers) into
   * workflow history, server logs, and the Temporal UI, which is a surprising default for a plain
   * observability label.
   */
  private String buildSummary() {
    return "chat: " + (modelName != null ? modelName : "default");
  }

  private ChatModelTypes.ChatModelActivityInput createActivityInput(Prompt prompt) {
    // Convert messages
    List<ChatModelTypes.Message> messages =
        prompt.getInstructions().stream()
            .flatMap(msg -> toActivityMessages(msg).stream())
            .collect(Collectors.toList());

    // Convert options — carry both the common scalars (fallback path) and a serialized blob of
    // the caller's exact ChatOptions subclass (primary path on the activity side, which
    // preserves provider-specific fields like OpenAI reasoning_effort).
    ChatModelTypes.ModelOptions modelOptions = null;
    if (prompt.getOptions() != null) {
      ChatOptions opts = prompt.getOptions();
      String chatOptionsClass = null;
      String chatOptionsJson = null;
      try {
        chatOptionsJson = OPTIONS_MAPPER.writeValueAsString(opts);
        chatOptionsClass = opts.getClass().getName();
      } catch (JsonProcessingException e) {
        log.debug(
            "Could not JSON-serialize ChatOptions of type {}; activity will fall back to"
                + " common-field path. Cause: {}",
            opts.getClass().getName(),
            e.getMessage());
      }
      modelOptions =
          new ChatModelTypes.ModelOptions(
              opts.getModel(),
              opts.getFrequencyPenalty(),
              opts.getMaxTokens(),
              opts.getPresencePenalty(),
              opts.getStopSequences(),
              opts.getTemperature(),
              opts.getTopK(),
              opts.getTopP(),
              chatOptionsClass,
              chatOptionsJson);
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
      ChatModelTypes.checkMediaSize(data);
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
      builder.metadata(toResponseMetadata(output.metadata()));
    }
    return builder.build();
  }

  private ChatResponseMetadata toResponseMetadata(
      ChatModelTypes.ChatModelActivityOutput.ChatResponseMetadata md) {
    ChatResponseMetadata.Builder b = ChatResponseMetadata.builder().model(md.model());
    Usage usage = toUsage(md.usage());
    if (usage != null) {
      b.usage(usage);
    }
    RateLimit rateLimit = toRateLimit(md.rateLimit());
    if (rateLimit != null) {
      b.rateLimit(rateLimit);
    }
    return b.build();
  }

  private Usage toUsage(ChatModelTypes.ChatModelActivityOutput.ChatResponseMetadata.Usage u) {
    if (u == null) {
      return null;
    }
    return new DefaultUsage(u.promptTokens(), u.completionTokens(), u.totalTokens());
  }

  private RateLimit toRateLimit(
      ChatModelTypes.ChatModelActivityOutput.ChatResponseMetadata.RateLimit r) {
    if (r == null) {
      return null;
    }
    return new RateLimit() {
      @Override
      public Long getRequestsLimit() {
        return r.requestLimit();
      }

      @Override
      public Long getRequestsRemaining() {
        return r.requestRemaining();
      }

      @Override
      public java.time.Duration getRequestsReset() {
        return r.requestReset();
      }

      @Override
      public Long getTokensLimit() {
        return r.tokenLimit();
      }

      @Override
      public Long getTokensRemaining() {
        return r.tokenRemaining();
      }

      @Override
      public java.time.Duration getTokensReset() {
        return r.tokenReset();
      }
    };
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
