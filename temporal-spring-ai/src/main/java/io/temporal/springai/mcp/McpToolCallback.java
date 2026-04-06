package io.temporal.springai.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * A {@link ToolCallback} implementation that executes MCP tools via Temporal activities.
 *
 * <p>This class bridges MCP tools with Spring AI's tool calling system, allowing AI models to call
 * MCP server tools through durable Temporal activities.
 *
 * <h2>Usage in Workflows</h2>
 *
 * <pre>{@code
 * @WorkflowInit
 * public MyWorkflowImpl() {
 *     // Create an MCP client
 *     ActivityMcpClient mcpClient = ActivityMcpClient.create();
 *
 *     // Convert MCP tools to ToolCallbacks
 *     List<ToolCallback> mcpTools = McpToolCallback.fromMcpClient(mcpClient);
 *
 *     // Use with TemporalChatClient
 *     this.chatClient = TemporalChatClient.builder(chatModel)
 *             .defaultToolCallbacks(mcpTools)
 *             .build();
 * }
 * }</pre>
 *
 * @see ActivityMcpClient
 * @see McpClientActivity
 */
public class McpToolCallback implements ToolCallback {

  private final ActivityMcpClient client;
  private final String clientName;
  private final McpSchema.Tool tool;
  private final ToolDefinition toolDefinition;

  /**
   * Creates a new McpToolCallback for a specific MCP tool.
   *
   * @param client the MCP client to use for tool calls
   * @param clientName the name of the MCP client that provides this tool
   * @param tool the tool definition
   * @param toolNamePrefix the prefix to use for the tool name (usually the MCP server name)
   */
  public McpToolCallback(
      ActivityMcpClient client, String clientName, McpSchema.Tool tool, String toolNamePrefix) {
    this.client = client;
    this.clientName = clientName;
    this.tool = tool;

    // Cache the tool definition at construction time to avoid activity calls in queries
    String prefixedName = McpToolUtils.prefixedToolName(toolNamePrefix, tool.name());
    this.toolDefinition =
        DefaultToolDefinition.builder()
            .name(prefixedName)
            .description(tool.description())
            .inputSchema(ModelOptionsUtils.toJsonString(tool.inputSchema()))
            .build();
  }

  /**
   * Creates ToolCallbacks for all tools from all MCP clients.
   *
   * <p>This method discovers all available tools from the MCP clients and wraps them as
   * ToolCallbacks that execute through Temporal activities.
   *
   * @param client the MCP client
   * @return list of ToolCallbacks for all discovered tools
   */
  public static List<ToolCallback> fromMcpClient(ActivityMcpClient client) {
    // Get client info upfront for tool name prefixes
    Map<String, McpSchema.Implementation> clientInfo = client.getClientInfo();

    Map<String, McpSchema.ListToolsResult> toolsMap = client.listTools();
    return toolsMap.entrySet().stream()
        .flatMap(
            entry -> {
              String clientName = entry.getKey();
              McpSchema.Implementation impl = clientInfo.get(clientName);
              String prefix = impl != null ? impl.name() : clientName;

              return entry.getValue().tools().stream()
                  .map(
                      tool -> (ToolCallback) new McpToolCallback(client, clientName, tool, prefix));
            })
        .toList();
  }

  @Override
  public ToolDefinition getToolDefinition() {
    return toolDefinition;
  }

  @Override
  public String call(String toolInput) {
    Map<String, Object> arguments = ModelOptionsUtils.jsonToMap(toolInput);

    // Use the original tool name (not prefixed) when calling the MCP server
    McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(tool.name(), arguments);
    McpSchema.CallToolResult result = client.callTool(clientName, request);

    // Return the result as-is (including errors) so the AI can handle them.
    // For example, an "access denied" error lets the AI suggest a valid path.
    return ModelOptionsUtils.toJsonString(result.content());
  }

  /**
   * Returns the name of the MCP client that provides this tool.
   *
   * @return the client name
   */
  public String getClientName() {
    return clientName;
  }

  /**
   * Returns the original tool definition from the MCP server.
   *
   * @return the tool definition
   */
  public McpSchema.Tool getMcpTool() {
    return tool;
  }
}
