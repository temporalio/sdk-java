package io.temporal.springai.autoconfigure;

import io.temporal.springai.plugin.McpPlugin;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for MCP (Model Context Protocol) integration with Temporal.
 *
 * <p>Conditionally creates a {@link McpPlugin} when {@code spring-ai-mcp} and the MCP client
 * library are on the classpath.
 */
@AutoConfiguration(after = SpringAiTemporalAutoConfiguration.class)
@ConditionalOnClass(name = "io.modelcontextprotocol.client.McpSyncClient")
public class SpringAiMcpAutoConfiguration {

  @Bean
  public McpPlugin mcpPlugin() {
    return new McpPlugin();
  }
}
