package io.temporal.springai.autoconfigure;

import io.temporal.springai.plugin.SpringAiPlugin;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Import;

/**
 * Auto-configuration for the Spring AI Temporal plugin.
 *
 * <p>Automatically registers {@link SpringAiPlugin} as a bean when Spring AI and Temporal SDK are
 * on the classpath. The plugin then auto-registers Spring AI activities with all Temporal workers.
 */
@AutoConfiguration
@ConditionalOnClass(
    name = {"org.springframework.ai.chat.model.ChatModel", "io.temporal.worker.Worker"})
@Import(SpringAiPlugin.class)
public class SpringAiTemporalAutoConfiguration {}
