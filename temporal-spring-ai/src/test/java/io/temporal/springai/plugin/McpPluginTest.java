package io.temporal.springai.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import io.temporal.springai.mcp.McpClientActivityImpl;
import io.temporal.worker.Worker;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationContext;

class McpPluginTest {

  @Test
  void discoversMcpClientBeansByType() {
    McpSyncClient clientA = mockClientNamed("alpha");
    McpSyncClient clientB = mockClientNamed("beta");

    // Spring's getBeansOfType keeps insertion order via LinkedHashMap; use that for determinism.
    Map<String, McpSyncClient> beans = new LinkedHashMap<>();
    beans.put("mcpClientAlpha", clientA);
    beans.put("mcpClientBeta", clientB);

    ApplicationContext ctx = mock(ApplicationContext.class);
    when(ctx.getBeansOfType(McpSyncClient.class)).thenReturn(beans);

    McpPlugin plugin = new McpPlugin();
    plugin.setApplicationContext(ctx);

    Worker worker = mock(Worker.class);
    plugin.initializeWorker("mcp-tq", worker);

    ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
    verify(worker, atLeastOnce()).registerActivitiesImplementations(captor.capture());
    Object registered = captor.getValue();
    assertEquals(McpClientActivityImpl.class, registered.getClass());

    // Duplicate-name protection in McpClientActivityImpl still fires if two clients share a
    // clientInfo().name(); here they differ ("alpha" vs "beta") so construction succeeds.
  }

  @Test
  void noMcpBeans_defersWorker_thenClearsAfterSingletonsInstantiated() {
    ApplicationContext ctx = mock(ApplicationContext.class);
    when(ctx.getBeansOfType(McpSyncClient.class)).thenReturn(Map.of());

    McpPlugin plugin = new McpPlugin();
    plugin.setApplicationContext(ctx);

    Worker worker = mock(Worker.class);
    plugin.initializeWorker("mcp-tq", worker);

    // No beans → nothing registered yet, worker queued for deferred attempt.
    verifyNoInteractions(worker);

    plugin.afterSingletonsInstantiated();

    // Still no beans — the deferred attempt also finds nothing and doesn't crash.
    verify(worker, org.mockito.Mockito.never()).registerActivitiesImplementations((Object[]) any());
  }

  @Test
  void beansAppearLate_registeredViaAfterSingletonsInstantiated() {
    ApplicationContext ctx = mock(ApplicationContext.class);
    // First lookup returns empty (Spring AI MCP bean hasn't been created yet when
    // initializeWorker runs).
    when(ctx.getBeansOfType(McpSyncClient.class))
        .thenReturn(Map.of())
        .thenReturn(Map.of("mcpClient", mockClientNamed("late")));

    McpPlugin plugin = new McpPlugin();
    plugin.setApplicationContext(ctx);

    Worker worker = mock(Worker.class);
    plugin.initializeWorker("mcp-tq", worker);
    verifyNoInteractions(worker);

    plugin.afterSingletonsInstantiated();

    ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
    verify(worker, atLeastOnce()).registerActivitiesImplementations(captor.capture());
    assertEquals(McpClientActivityImpl.class, captor.getValue().getClass());
  }

  private static McpSyncClient mockClientNamed(String name) {
    McpSyncClient client = mock(McpSyncClient.class);
    McpSchema.Implementation info = new McpSchema.Implementation(name, "1.0.0");
    when(client.getClientInfo()).thenReturn(info);
    return client;
  }
}
