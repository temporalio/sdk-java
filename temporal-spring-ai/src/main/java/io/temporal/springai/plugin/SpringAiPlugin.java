package io.temporal.springai.plugin;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.SimplePlugin;
import io.temporal.springai.activity.ChatModelActivityImpl;
import io.temporal.worker.Worker;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;

/**
 * Core Temporal plugin that registers {@link io.temporal.springai.activity.ChatModelActivity} with
 * Temporal workers.
 *
 * <p>This plugin handles the required ChatModel integration. Optional integrations (VectorStore,
 * EmbeddingModel, MCP) are handled by separate plugins that are conditionally created by
 * auto-configuration:
 *
 * <ul>
 *   <li>{@link VectorStorePlugin} - when {@code spring-ai-rag} is on the classpath
 *   <li>{@link EmbeddingModelPlugin} - when {@code spring-ai-rag} is on the classpath
 *   <li>{@link McpPlugin} - when {@code spring-ai-mcp} is on the classpath
 * </ul>
 *
 * <h2>In Workflows</h2>
 *
 * <pre>{@code
 * @WorkflowInit
 * public MyWorkflowImpl() {
 *     ActivityChatModel chatModel = ActivityChatModel.forDefault();
 *     this.chatClient = TemporalChatClient.builder(chatModel).build();
 * }
 * }</pre>
 *
 * @see io.temporal.springai.activity.ChatModelActivity
 * @see io.temporal.springai.model.ActivityChatModel
 */
public class SpringAiPlugin extends SimplePlugin {

  private static final Logger log = LoggerFactory.getLogger(SpringAiPlugin.class);

  /** The name used for the default chat model when none is specified. */
  public static final String DEFAULT_MODEL_NAME = "default";

  private final Map<String, ChatModel> chatModels;
  private final String defaultModelName;

  /**
   * Creates a new SpringAiPlugin with the given ChatModel.
   *
   * @param chatModel the Spring AI chat model to wrap as an activity
   */
  public SpringAiPlugin(ChatModel chatModel) {
    this(Map.of(DEFAULT_MODEL_NAME, chatModel), null, Map.of());
  }

  /**
   * Creates a new SpringAiPlugin with multiple ChatModels.
   *
   * @param chatModels map of bean names to ChatModel instances
   * @param primaryChatModel the primary chat model (used to determine default), or null
   */
  public SpringAiPlugin(Map<String, ChatModel> chatModels, @Nullable ChatModel primaryChatModel) {
    this(chatModels, primaryChatModel, Map.of());
  }

  /**
   * Creates a new SpringAiPlugin with multiple ChatModels and per-model {@link ActivityOptions}.
   *
   * <p>Entries in {@code perModelOptions} are keyed by chat-model bean name and consulted by {@link
   * io.temporal.springai.model.ActivityChatModel#forModel(String)} (and by {@link
   * io.temporal.springai.model.ActivityChatModel#forDefault()} via {@link #DEFAULT_MODEL_NAME}).
   * Callers who pass explicit {@code ActivityOptions} to a factory bypass this map entirely.
   *
   * @param chatModels map of bean names to ChatModel instances
   * @param primaryChatModel the primary chat model (used to determine default), or null
   * @param perModelOptions per-model-name ActivityOptions overrides; may be empty
   */
  public SpringAiPlugin(
      Map<String, ChatModel> chatModels,
      @Nullable ChatModel primaryChatModel,
      Map<String, ActivityOptions> perModelOptions) {
    super("io.temporal.spring-ai");

    if (chatModels == null || chatModels.isEmpty()) {
      throw new IllegalArgumentException("At least one ChatModel bean is required");
    }

    this.chatModels = new LinkedHashMap<>(chatModels);

    if (primaryChatModel != null) {
      String primaryName =
          chatModels.entrySet().stream()
              .filter(e -> e.getValue() == primaryChatModel)
              .map(Map.Entry::getKey)
              .findFirst()
              .orElse(chatModels.keySet().iterator().next());
      this.defaultModelName = primaryName;
    } else {
      this.defaultModelName = chatModels.keySet().iterator().next();
    }

    SpringAiPluginOptions.register(perModelOptions);

    if (chatModels.size() > 1) {
      log.info(
          "Registered {} chat models: {} (default: {})",
          chatModels.size(),
          chatModels.keySet(),
          defaultModelName);
    }
    if (perModelOptions != null && !perModelOptions.isEmpty()) {
      log.info(
          "Registered per-model ActivityOptions overrides for {} model(s): {}",
          perModelOptions.size(),
          perModelOptions.keySet());
    }
  }

  @Override
  public void initializeWorker(@Nonnull String taskQueue, @Nonnull Worker worker) {
    ChatModelActivityImpl chatModelActivityImpl =
        new ChatModelActivityImpl(chatModels, defaultModelName);
    worker.registerActivitiesImplementations(chatModelActivityImpl);

    String modelInfo = chatModels.size() > 1 ? " (" + chatModels.size() + " models)" : "";
    log.info("Registered ChatModelActivity{} for task queue {}", modelInfo, taskQueue);
  }

  /**
   * Returns the default ChatModel wrapped by this plugin.
   *
   * @return the default chat model
   */
  public ChatModel getChatModel() {
    return chatModels.get(defaultModelName);
  }

  /**
   * Returns a specific ChatModel by bean name.
   *
   * @param modelName the bean name of the chat model
   * @return the chat model
   * @throws IllegalArgumentException if no model with that name exists
   */
  public ChatModel getChatModel(String modelName) {
    ChatModel model = chatModels.get(modelName);
    if (model == null) {
      throw new IllegalArgumentException(
          "No chat model with name '" + modelName + "'. Available models: " + chatModels.keySet());
    }
    return model;
  }

  /**
   * Returns all ChatModels wrapped by this plugin, keyed by bean name.
   *
   * @return unmodifiable map of chat models
   */
  public Map<String, ChatModel> getChatModels() {
    return Collections.unmodifiableMap(chatModels);
  }

  /**
   * Returns the name of the default chat model.
   *
   * @return the default model name
   */
  public String getDefaultModelName() {
    return defaultModelName;
  }
}
