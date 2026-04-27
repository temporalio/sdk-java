package io.temporal.springai.autoconfigure;

import io.temporal.springai.plugin.VectorStorePlugin;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for VectorStore integration with Temporal.
 *
 * <p>Conditionally creates a {@link VectorStorePlugin} when {@code spring-ai-rag} is on the
 * classpath and a {@link VectorStore} bean is available.
 */
@AutoConfiguration(after = SpringAiTemporalAutoConfiguration.class)
@ConditionalOnClass(name = "org.springframework.ai.vectorstore.VectorStore")
@ConditionalOnBean(VectorStore.class)
public class SpringAiVectorStoreAutoConfiguration {

  @Bean
  public VectorStorePlugin vectorStorePlugin(VectorStore vectorStore) {
    return new VectorStorePlugin(vectorStore);
  }
}
