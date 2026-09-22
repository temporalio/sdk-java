package io.temporal.springai.autoconfigure;

import io.temporal.activity.ActivityOptions;
import io.temporal.springai.plugin.SpringAiPlugin;
import java.util.Map;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

/**
 * Core auto-configuration for the Spring AI Temporal plugin.
 *
 * <p>Creates the {@link SpringAiPlugin} bean which registers {@link
 * io.temporal.springai.activity.ChatModelActivity} with all Temporal workers.
 *
 * <p>Optional integrations are handled by separate auto-configuration classes:
 *
 * <ul>
 *   <li>{@link SpringAiVectorStoreAutoConfiguration} - VectorStore support
 *   <li>{@link SpringAiEmbeddingAutoConfiguration} - EmbeddingModel support
 *   <li>{@link SpringAiMcpAutoConfiguration} - MCP support
 * </ul>
 */
@AutoConfiguration
@ConditionalOnClass(
    name = {"org.springframework.ai.chat.model.ChatModel", "io.temporal.worker.Worker"})
public class SpringAiTemporalAutoConfiguration {

  /**
   * Builds the {@link SpringAiPlugin}. Picks up an optional {@link ChatModelActivityOptions} bean
   * and forwards its map to the plugin as per-model {@link ActivityOptions} overrides. The wrapper
   * type exists so this auto-config can inject user options <em>by type</em> — trying to inject
   * {@code Map<String, ActivityOptions>} directly would trigger Spring's collection-of-beans
   * autowiring and sweep in any unrelated {@link ActivityOptions} bean in the context.
   *
   * <p>Example user config:
   *
   * <pre>{@code
   * @Bean
   * ChatModelActivityOptions chatModelActivityOptions() {
   *     return new ChatModelActivityOptions(Map.of(
   *         "reasoning", ActivityOptions.newBuilder(ActivityChatModel.defaultActivityOptions())
   *             .setStartToCloseTimeout(Duration.ofMinutes(15))
   *             .build()));
   * }
   * }</pre>
   */
  @Bean
  public SpringAiPlugin springAiPlugin(
      @Autowired Map<String, ChatModel> chatModels,
      ObjectProvider<ChatModel> primaryChatModel,
      ObjectProvider<ChatModelActivityOptions> perModelOptions) {
    ChatModelActivityOptions options =
        perModelOptions.getIfAvailable(ChatModelActivityOptions::empty);
    return new SpringAiPlugin(chatModels, primaryChatModel.getIfUnique(), options.byModelName());
  }
}
