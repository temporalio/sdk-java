package io.temporal.springai.plugin;

import io.temporal.common.SimplePlugin;
import io.temporal.springai.activity.VectorStoreActivityImpl;
import org.springframework.ai.vectorstore.VectorStore;

/**
 * Temporal plugin that registers {@link io.temporal.springai.activity.VectorStoreActivity} with
 * workers.
 *
 * <p>This plugin is conditionally created by auto-configuration when Spring AI's {@link
 * VectorStore} is on the classpath and a VectorStore bean is available. It can also be created
 * manually for non-auto-configured setups.
 */
public class VectorStorePlugin extends SimplePlugin {

  public VectorStorePlugin(VectorStore vectorStore) {
    super(
        SimplePlugin.newBuilder("io.temporal.spring-ai-vectorstore")
            .registerActivitiesImplementations(new VectorStoreActivityImpl(vectorStore)));
  }
}
