package io.temporal.springai.plugin;

import io.temporal.common.SimplePlugin;
import io.temporal.springai.activity.*;
import io.temporal.springai.tool.ExecuteToolLocalActivityImpl;
import io.temporal.worker.Worker;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Temporal plugin that integrates Spring AI components with Temporal workers.
 *
 * <p>This plugin automatically registers Spring AI-related activities with Temporal workers:
 *
 * <ul>
 *   <li>{@link ChatModelActivity} - wraps Spring AI's {@link ChatModel} for durable AI calls
 *   <li>{@link VectorStoreActivity} - wraps Spring AI's {@link VectorStore} for durable vector
 *       operations
 *   <li>{@link EmbeddingModelActivity} - wraps Spring AI's {@link EmbeddingModel} for durable
 *       embeddings
 *   <li>{@link io.temporal.springai.mcp.McpClientActivity} - wraps MCP clients for durable MCP tool
 *       calls
 * </ul>
 *
 * <p>The plugin detects Spring AI beans in the application context and creates the corresponding
 * Temporal activity implementations automatically. Only activities for available beans are
 * registered.
 *
 * <h2>Usage</h2>
 *
 * <p>Simply add this plugin to your Spring Boot application. It will be auto-detected and
 * registered with all workers:
 *
 * <pre>{@code
 * // In your Spring configuration or let Spring auto-detect via @Component
 * @Bean
 * public SpringAiPlugin springAiPlugin(ChatModel chatModel) {
 *     return new SpringAiPlugin(chatModel);
 * }
 *
 * // Or with all Spring AI components
 * @Bean
 * public SpringAiPlugin springAiPlugin(
 *         ChatModel chatModel,
 *         VectorStore vectorStore,
 *         EmbeddingModel embeddingModel) {
 *     return new SpringAiPlugin(chatModel, vectorStore, embeddingModel);
 * }
 * }</pre>
 *
 * <h2>In Workflows</h2>
 *
 * <p>Use the registered activities via stubs:
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
 * @see ChatModelActivity
 * @see VectorStoreActivity
 * @see EmbeddingModelActivity
 * @see io.temporal.springai.mcp.McpClientActivity
 * @see io.temporal.springai.model.ActivityChatModel
 */
@Component
public class SpringAiPlugin extends SimplePlugin
    implements ApplicationContextAware, SmartInitializingSingleton {

  private static final Logger log = LoggerFactory.getLogger(SpringAiPlugin.class);

  /** The name used for the default chat model when none is specified. */
  public static final String DEFAULT_MODEL_NAME = "default";

  private final Map<String, ChatModel> chatModels;
  private final String defaultModelName;
  private final VectorStore vectorStore;
  private final EmbeddingModel embeddingModel;
  // Stored as List<?> to avoid class loading when MCP is not on classpath
  private List<?> mcpClients = List.of();
  private ApplicationContext applicationContext;
  // Workers that need MCP activities registered after initialization
  private final List<Worker> pendingMcpWorkers = new ArrayList<>();

  /**
   * Creates a new SpringAiPlugin with the given ChatModel.
   *
   * @param chatModel the Spring AI chat model to wrap as an activity
   */
  public SpringAiPlugin(ChatModel chatModel) {
    this(chatModel, null, null);
  }

  /**
   * Creates a new SpringAiPlugin with the given Spring AI components.
   *
   * <p>When used with Spring autowiring, components that are not available in the application
   * context will be null and their corresponding activities won't be registered.
   *
   * @param chatModel the Spring AI chat model to wrap as an activity (required)
   * @param vectorStore the Spring AI vector store to wrap as an activity (optional)
   * @param embeddingModel the Spring AI embedding model to wrap as an activity (optional)
   */
  public SpringAiPlugin(
      ChatModel chatModel,
      @Nullable VectorStore vectorStore,
      @Nullable EmbeddingModel embeddingModel) {
    super("io.temporal.spring-ai");
    this.chatModels = Map.of(DEFAULT_MODEL_NAME, chatModel);
    this.defaultModelName = DEFAULT_MODEL_NAME;
    this.vectorStore = vectorStore;
    this.embeddingModel = embeddingModel;
  }

  /**
   * Creates a new SpringAiPlugin with multiple ChatModels.
   *
   * <p>When used with Spring autowiring and multiple ChatModel beans, Spring will inject a map of
   * all ChatModel beans keyed by their bean names. The first bean in the map (or one marked
   * with @Primary) is used as the default.
   *
   * <p>Example usage in workflows:
   *
   * <pre>{@code
   * // Use the default model
   * ActivityChatModel defaultModel = ActivityChatModel.forDefault();
   *
   * // Use a specific model by bean name
   * ActivityChatModel openAiModel = ActivityChatModel.forModel("openAiChatModel");
   * ActivityChatModel anthropicModel = ActivityChatModel.forModel("anthropicChatModel");
   * }</pre>
   *
   * @param chatModels map of bean names to ChatModel instances
   * @param primaryChatModel the primary chat model (used to determine default)
   * @param vectorStore the Spring AI vector store to wrap as an activity (optional)
   * @param embeddingModel the Spring AI embedding model to wrap as an activity (optional)
   */
  @Autowired
  public SpringAiPlugin(
      @Nullable @Autowired(required = false) Map<String, ChatModel> chatModels,
      @Nullable @Autowired(required = false) ChatModel primaryChatModel,
      @Nullable @Autowired(required = false) VectorStore vectorStore,
      @Nullable @Autowired(required = false) EmbeddingModel embeddingModel) {
    super("io.temporal.spring-ai");

    if (chatModels == null || chatModels.isEmpty()) {
      throw new IllegalArgumentException("At least one ChatModel bean is required");
    }

    // Use LinkedHashMap to preserve insertion order
    this.chatModels = new LinkedHashMap<>(chatModels);

    // Find the default model name: prefer the primary bean, otherwise use first entry
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

    this.vectorStore = vectorStore;
    this.embeddingModel = embeddingModel;

    if (chatModels.size() > 1) {
      log.info(
          "Registered {} chat models: {} (default: {})",
          chatModels.size(),
          chatModels.keySet(),
          defaultModelName);
    }
  }

  @Override
  public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
    this.applicationContext = applicationContext;
  }

  /**
   * Sets the MCP clients for this plugin.
   *
   * <p>This setter can be called by external configuration when MCP is on the classpath. The method
   * signature uses {@code List<?>} to avoid loading MCP classes when MCP is not available.
   *
   * @param mcpClients list of MCP clients (must be {@code List<McpSyncClient>})
   */
  public void setMcpClients(@Nullable List<?> mcpClients) {
    this.mcpClients = mcpClients != null ? mcpClients : List.of();
    if (!this.mcpClients.isEmpty()) {
      log.info("MCP clients configured: {}", this.mcpClients.size());
    }
  }

  /**
   * Looks up MCP clients from the ApplicationContext if not already set. Spring AI MCP
   * auto-configuration creates a bean named "mcpSyncClients" containing a List of McpSyncClient
   * instances.
   */
  @SuppressWarnings("unchecked")
  private List<?> getMcpClients() {
    if (!mcpClients.isEmpty()) {
      return mcpClients;
    }

    // Try to look up MCP clients from ApplicationContext
    // Spring AI MCP creates a "mcpSyncClients" bean which is a List<McpSyncClient>
    if (applicationContext != null && applicationContext.containsBean("mcpSyncClients")) {
      try {
        Object bean = applicationContext.getBean("mcpSyncClients");
        if (bean instanceof List<?> clientList && !clientList.isEmpty()) {
          mcpClients = (List<?>) clientList;
          log.info("Found {} MCP client(s) in ApplicationContext", mcpClients.size());
        }
      } catch (Exception e) {
        log.debug("Failed to get mcpSyncClients bean: {}", e.getMessage());
      }
    }

    return mcpClients;
  }

  @Override
  public void initializeWorker(@Nonnull String taskQueue, @Nonnull Worker worker) {
    List<String> registeredActivities = new ArrayList<>();

    // Register the ChatModelActivity implementation with all chat models
    ChatModelActivityImpl chatModelActivityImpl =
        new ChatModelActivityImpl(chatModels, defaultModelName);
    worker.registerActivitiesImplementations(chatModelActivityImpl);
    registeredActivities.add(
        "ChatModelActivity" + (chatModels.size() > 1 ? " (" + chatModels.size() + " models)" : ""));

    // Register VectorStoreActivity if VectorStore is available
    if (vectorStore != null) {
      VectorStoreActivityImpl vectorStoreActivityImpl = new VectorStoreActivityImpl(vectorStore);
      worker.registerActivitiesImplementations(vectorStoreActivityImpl);
      registeredActivities.add("VectorStoreActivity");
    }

    // Register EmbeddingModelActivity if EmbeddingModel is available
    if (embeddingModel != null) {
      EmbeddingModelActivityImpl embeddingModelActivityImpl =
          new EmbeddingModelActivityImpl(embeddingModel);
      worker.registerActivitiesImplementations(embeddingModelActivityImpl);
      registeredActivities.add("EmbeddingModelActivity");
    }

    // Register ExecuteToolLocalActivity for LocalActivityToolCallbackWrapper support
    ExecuteToolLocalActivityImpl executeToolLocalActivity = new ExecuteToolLocalActivityImpl();
    worker.registerActivitiesImplementations(executeToolLocalActivity);
    registeredActivities.add("ExecuteToolLocalActivity");

    // Try to register McpClientActivity if MCP clients are already available
    List<?> clients = getMcpClients();
    if (!clients.isEmpty()) {
      registerMcpActivity(worker, clients, registeredActivities);
    } else {
      // MCP clients may be created later; store worker for deferred registration
      pendingMcpWorkers.add(worker);
      log.debug(
          "MCP clients not yet available; will attempt registration after all beans are initialized");
    }

    log.info(
        "Registered Spring AI activities for task queue {}: {}",
        taskQueue,
        String.join(", ", registeredActivities));
  }

  /**
   * Called after all singleton beans have been instantiated. This is where we register MCP
   * activities if they weren't available during initializeWorker.
   */
  @Override
  public void afterSingletonsInstantiated() {
    if (pendingMcpWorkers.isEmpty()) {
      return;
    }

    // Try to find MCP clients now that all beans are created
    List<?> clients = getMcpClients();
    if (clients.isEmpty()) {
      log.debug("No MCP clients found after all beans initialized");
      pendingMcpWorkers.clear();
      return;
    }

    // Register MCP activities with all pending workers
    for (Worker worker : pendingMcpWorkers) {
      List<String> registered = new ArrayList<>();
      registerMcpActivity(worker, clients, registered);
      if (!registered.isEmpty()) {
        log.info("Registered deferred MCP activities: {}", String.join(", ", registered));
      }
    }
    pendingMcpWorkers.clear();
  }

  /** Registers McpClientActivity with a worker using reflection to avoid MCP class dependencies. */
  private void registerMcpActivity(
      Worker worker, List<?> clients, List<String> registeredActivities) {
    try {
      // Use reflection to avoid loading MCP classes when not on classpath
      Class<?> mcpActivityClass = Class.forName("io.temporal.springai.mcp.McpClientActivityImpl");
      Object mcpClientActivity = mcpActivityClass.getConstructor(List.class).newInstance(clients);
      worker.registerActivitiesImplementations(mcpClientActivity);
      registeredActivities.add("McpClientActivity (" + clients.size() + " clients)");
    } catch (ClassNotFoundException e) {
      log.warn("MCP clients configured but MCP support classes not found on classpath");
    } catch (ReflectiveOperationException e) {
      log.error("Failed to instantiate McpClientActivityImpl", e);
    }
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

  /**
   * Returns the VectorStore wrapped by this plugin, if available.
   *
   * @return the vector store, or null if not configured
   */
  @Nullable
  public VectorStore getVectorStore() {
    return vectorStore;
  }

  /**
   * Returns the EmbeddingModel wrapped by this plugin, if available.
   *
   * @return the embedding model, or null if not configured
   */
  @Nullable
  public EmbeddingModel getEmbeddingModel() {
    return embeddingModel;
  }
}
