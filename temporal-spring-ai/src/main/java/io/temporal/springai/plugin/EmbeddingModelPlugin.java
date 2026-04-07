package io.temporal.springai.plugin;

import io.temporal.common.SimplePlugin;
import io.temporal.springai.activity.EmbeddingModelActivityImpl;
import io.temporal.worker.Worker;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;

/**
 * Temporal plugin that registers {@link io.temporal.springai.activity.EmbeddingModelActivity} with
 * workers.
 *
 * <p>This plugin is conditionally created by auto-configuration when Spring AI's {@link
 * EmbeddingModel} is on the classpath and an EmbeddingModel bean is available.
 */
public class EmbeddingModelPlugin extends SimplePlugin {

  private static final Logger log = LoggerFactory.getLogger(EmbeddingModelPlugin.class);

  private final EmbeddingModel embeddingModel;

  public EmbeddingModelPlugin(EmbeddingModel embeddingModel) {
    super("io.temporal.spring-ai-embedding");
    this.embeddingModel = embeddingModel;
  }

  @Override
  public void initializeWorker(@Nonnull String taskQueue, @Nonnull Worker worker) {
    worker.registerActivitiesImplementations(new EmbeddingModelActivityImpl(embeddingModel));
    log.info("Registered EmbeddingModelActivity for task queue {}", taskQueue);
  }
}
