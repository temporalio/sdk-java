package io.temporal.springai.autoconfigure;

import io.temporal.common.SimplePlugin;
import io.temporal.springai.activity.EmbeddingModelActivityImpl;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for EmbeddingModel integration with Temporal.
 *
 * <p>Conditionally creates a plugin that registers {@link
 * io.temporal.springai.activity.EmbeddingModelActivity} when {@code spring-ai-rag} is on the
 * classpath and an {@link EmbeddingModel} bean is available.
 */
@AutoConfiguration(after = SpringAiTemporalAutoConfiguration.class)
@ConditionalOnClass(name = "org.springframework.ai.embedding.EmbeddingModel")
@ConditionalOnBean(EmbeddingModel.class)
public class SpringAiEmbeddingAutoConfiguration {

  @Bean
  public SimplePlugin embeddingModelPlugin(EmbeddingModel embeddingModel) {
    return SimplePlugin.newBuilder("io.temporal.spring-ai-embedding")
        .registerActivitiesImplementations(new EmbeddingModelActivityImpl(embeddingModel))
        .build();
  }
}
