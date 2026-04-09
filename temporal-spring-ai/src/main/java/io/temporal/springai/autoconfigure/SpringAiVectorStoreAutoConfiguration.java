package io.temporal.springai.autoconfigure;

import io.temporal.common.SimplePlugin;
import io.temporal.springai.activity.VectorStoreActivityImpl;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for VectorStore integration with Temporal.
 *
 * <p>Conditionally creates a plugin that registers {@link
 * io.temporal.springai.activity.VectorStoreActivity} when {@code spring-ai-rag} is on the classpath
 * and a {@link VectorStore} bean is available.
 */
@AutoConfiguration(after = SpringAiTemporalAutoConfiguration.class)
@ConditionalOnClass(name = "org.springframework.ai.vectorstore.VectorStore")
@ConditionalOnBean(VectorStore.class)
public class SpringAiVectorStoreAutoConfiguration {

  @Bean
  public SimplePlugin vectorStorePlugin(VectorStore vectorStore) {
    return SimplePlugin.newBuilder("io.temporal.spring-ai-vectorstore")
        .registerActivitiesImplementations(new VectorStoreActivityImpl(vectorStore))
        .build();
  }
}
