package io.temporal.springai.plugin;

import io.temporal.common.SimplePlugin;
import io.temporal.springai.activity.EmbeddingModelActivityImpl;
import org.springframework.ai.embedding.EmbeddingModel;

/**
 * Temporal plugin that registers {@link io.temporal.springai.activity.EmbeddingModelActivity} with
 * workers.
 *
 * <p>This plugin is conditionally created by auto-configuration when Spring AI's {@link
 * EmbeddingModel} is on the classpath and an EmbeddingModel bean is available. It can also be
 * created manually for non-auto-configured setups.
 */
public class EmbeddingModelPlugin extends SimplePlugin {

  public EmbeddingModelPlugin(EmbeddingModel embeddingModel) {
    super(
        SimplePlugin.newBuilder("io.temporal.spring-ai-embedding")
            .registerActivitiesImplementations(new EmbeddingModelActivityImpl(embeddingModel)));
  }
}
