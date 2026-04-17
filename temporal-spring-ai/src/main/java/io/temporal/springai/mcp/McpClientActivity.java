package io.temporal.springai.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import java.util.Map;

/**
 * Activity interface for interacting with MCP (Model Context Protocol) clients.
 *
 * <p>This activity provides durable access to MCP servers, allowing workflows to discover and call
 * MCP tools as Temporal activities with full retry and timeout support.
 *
 * <p>The activity implementation ({@link McpClientActivityImpl}) is automatically registered by the
 * plugin when MCP clients are available in the Spring context.
 *
 * @see ActivityMcpClient
 * @see McpToolCallback
 */
@ActivityInterface(namePrefix = "MCP-Client-")
public interface McpClientActivity {

  /**
   * Gets the server capabilities for all connected MCP clients.
   *
   * @return map of client name to server capabilities
   */
  @ActivityMethod
  Map<String, McpSchema.ServerCapabilities> getServerCapabilities();

  /**
   * Gets client info for all connected MCP clients.
   *
   * @return map of client name to client implementation info
   */
  @ActivityMethod
  Map<String, McpSchema.Implementation> getClientInfo();

  /**
   * Calls a tool on a specific MCP client.
   *
   * @param clientName the name of the MCP client
   * @param request the tool call request
   * @return the tool call result
   */
  @ActivityMethod
  McpSchema.CallToolResult callTool(String clientName, McpSchema.CallToolRequest request);

  /**
   * Lists all available tools from all connected MCP clients.
   *
   * @return map of client name to list of tools
   */
  @ActivityMethod
  Map<String, McpSchema.ListToolsResult> listTools();
}
