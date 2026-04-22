package io.temporal.springai.plugin;

import io.modelcontextprotocol.client.McpSyncClient;
import io.temporal.common.SimplePlugin;
import io.temporal.springai.mcp.McpClientActivityImpl;
import io.temporal.worker.Worker;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

/**
 * Temporal plugin that registers {@link io.temporal.springai.mcp.McpClientActivity} with workers.
 *
 * <p>This plugin is conditionally created by auto-configuration when MCP classes are on the
 * classpath. MCP clients may be created late by Spring AI's auto-configuration, so this plugin
 * supports deferred registration via {@link SmartInitializingSingleton}.
 */
public class McpPlugin extends SimplePlugin
    implements ApplicationContextAware, SmartInitializingSingleton {

  private static final Logger log = LoggerFactory.getLogger(McpPlugin.class);

  private List<McpSyncClient> mcpClients = List.of();
  private ApplicationContext applicationContext;
  private final List<Worker> pendingWorkers = new ArrayList<>();

  public McpPlugin() {
    super("io.temporal.spring-ai-mcp");
  }

  @Override
  public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
    this.applicationContext = applicationContext;
  }

  @SuppressWarnings("unchecked")
  private List<McpSyncClient> getMcpClients() {
    if (!mcpClients.isEmpty()) {
      return mcpClients;
    }

    if (applicationContext != null && applicationContext.containsBean("mcpSyncClients")) {
      try {
        Object bean = applicationContext.getBean("mcpSyncClients");
        if (bean instanceof List<?> clientList && !clientList.isEmpty()) {
          mcpClients = (List<McpSyncClient>) clientList;
          log.info("Found {} MCP client(s) in ApplicationContext", mcpClients.size());
        }
      } catch (Exception e) {
        log.debug("Failed to get mcpSyncClients bean: {}", e.getMessage());
      }
    }

    return mcpClients;
  }

  @Override
  public void initializeWorker(@Nonnull String taskQueue, @Nonnull Worker worker) {
    List<McpSyncClient> clients = getMcpClients();
    if (!clients.isEmpty()) {
      worker.registerActivitiesImplementations(new McpClientActivityImpl(clients));
      log.info(
          "Registered McpClientActivity ({} clients) for task queue {}", clients.size(), taskQueue);
    } else {
      pendingWorkers.add(worker);
      log.debug("MCP clients not yet available; will attempt registration after initialization");
    }
  }

  @Override
  public void afterSingletonsInstantiated() {
    if (pendingWorkers.isEmpty()) {
      return;
    }

    List<McpSyncClient> clients = getMcpClients();
    if (clients.isEmpty()) {
      log.debug("No MCP clients found after all beans initialized");
      pendingWorkers.clear();
      return;
    }

    for (Worker worker : pendingWorkers) {
      worker.registerActivitiesImplementations(new McpClientActivityImpl(clients));
      log.info("Registered deferred McpClientActivity ({} clients)", clients.size());
    }
    pendingWorkers.clear();
  }
}
