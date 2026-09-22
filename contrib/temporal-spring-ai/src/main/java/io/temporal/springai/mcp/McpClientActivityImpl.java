package io.temporal.springai.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import io.temporal.failure.ApplicationFailure;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Implementation of {@link McpClientActivity} that delegates to Spring AI MCP clients.
 *
 * <p>This activity provides durable access to MCP servers. It is automatically registered by the
 * plugin when MCP clients are available in the Spring context.
 */
public class McpClientActivityImpl implements McpClientActivity {

  private final Map<String, McpSyncClient> mcpClients;

  /**
   * Creates an activity implementation with the given MCP clients.
   *
   * @param mcpClients list of MCP sync clients from Spring context
   */
  public McpClientActivityImpl(List<McpSyncClient> mcpClients) {
    this.mcpClients =
        mcpClients.stream()
            .collect(
                Collectors.toMap(
                    c -> c.getClientInfo().name(),
                    c -> c,
                    (existing, duplicate) -> {
                      throw new IllegalArgumentException(
                          "Duplicate MCP client name: '"
                              + existing.getClientInfo().name()
                              + "'. Each MCP client must have a unique name.");
                    }));
  }

  @Override
  public Map<String, McpSchema.ServerCapabilities> getServerCapabilities() {
    return mcpClients.entrySet().stream()
        .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getServerCapabilities()));
  }

  @Override
  public Map<String, McpSchema.Implementation> getClientInfo() {
    return mcpClients.entrySet().stream()
        .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getClientInfo()));
  }

  @Override
  public McpSchema.CallToolResult callTool(String clientName, McpSchema.CallToolRequest request) {
    McpSyncClient client = mcpClients.get(clientName);
    if (client == null) {
      throw ApplicationFailure.newBuilder()
          .setType("ClientNotFound")
          .setMessage(
              "MCP client '"
                  + clientName
                  + "' not found. Available clients: "
                  + mcpClients.keySet())
          .setNonRetryable(true)
          .build();
    }
    return client.callTool(request);
  }

  @Override
  public Map<String, McpSchema.ListToolsResult> listTools() {
    return mcpClients.entrySet().stream()
        .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().listTools()));
  }
}
