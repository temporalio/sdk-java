package io.temporal.springai.autoconfigure;

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

  @Bean
  public SpringAiPlugin springAiPlugin(
      @Autowired Map<String, ChatModel> chatModels, ObjectProvider<ChatModel> primaryChatModel) {
    return new SpringAiPlugin(chatModels, primaryChatModel.getIfUnique());
  }
}
