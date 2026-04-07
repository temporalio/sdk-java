package io.temporal.springai.plugin;

import io.temporal.common.SimplePlugin;
import io.temporal.springai.activity.VectorStoreActivityImpl;
import io.temporal.worker.Worker;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.VectorStore;

/**
 * Temporal plugin that registers {@link io.temporal.springai.activity.VectorStoreActivity} with
 * workers.
 *
 * <p>This plugin is conditionally created by auto-configuration when Spring AI's {@link
 * VectorStore} is on the classpath and a VectorStore bean is available.
 */
public class VectorStorePlugin extends SimplePlugin {

  private static final Logger log = LoggerFactory.getLogger(VectorStorePlugin.class);

  private final VectorStore vectorStore;

  public VectorStorePlugin(VectorStore vectorStore) {
    super("io.temporal.spring-ai-vectorstore");
    this.vectorStore = vectorStore;
  }

  @Override
  public void initializeWorker(@Nonnull String taskQueue, @Nonnull Worker worker) {
    worker.registerActivitiesImplementations(new VectorStoreActivityImpl(vectorStore));
    log.info("Registered VectorStoreActivity for task queue {}", taskQueue);
  }
}
