package io.temporal.springai.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.Map;

/**
 * A workflow-safe wrapper for MCP (Model Context Protocol) client operations.
 *
 * <p>This class provides access to MCP tools within Temporal workflows. All MCP operations are
 * executed as activities, providing durability, automatic retries, and timeout handling.
 *
 * <h2>Usage in Workflows</h2>
 *
 * <pre>{@code
 * @WorkflowInit
 * public MyWorkflowImpl() {
 *     // Create an MCP client with default options
 *     ActivityMcpClient mcpClient = ActivityMcpClient.create();
 *
 *     // Get tools from all connected MCP servers
 *     List<ToolCallback> mcpTools = McpToolCallback.fromMcpClient(mcpClient);
 *
 *     // Use with TemporalChatClient
 *     this.chatClient = TemporalChatClient.builder(chatModel)
 *             .defaultToolCallbacks(mcpTools)
 *             .build();
 * }
 * }</pre>
 *
 * <h2>MCP Server Configuration</h2>
 *
 * <p>MCP servers are configured in the worker's Spring context using Spring AI's MCP client
 * configuration. See the Spring AI MCP documentation for details.
 *
 * @see McpClientActivity
 * @see McpToolCallback
 */
public class ActivityMcpClient {

  /** Default timeout for MCP activity calls (30 seconds). */
  public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

  /** Default maximum retry attempts for MCP activity calls. */
  public static final int DEFAULT_MAX_ATTEMPTS = 3;

  private final McpClientActivity activity;
  private Map<String, McpSchema.ServerCapabilities> serverCapabilities;
  private Map<String, McpSchema.Implementation> clientInfo;

  /**
   * Creates a new ActivityMcpClient with the given activity stub.
   *
   * @param activity the activity stub for MCP operations
   */
  public ActivityMcpClient(McpClientActivity activity) {
    this.activity = activity;
  }

  /**
   * Creates an ActivityMcpClient with default options.
   *
   * <p><strong>Must be called from workflow code.</strong>
   *
   * @return a new ActivityMcpClient
   */
  public static ActivityMcpClient create() {
    return create(DEFAULT_TIMEOUT, DEFAULT_MAX_ATTEMPTS);
  }

  /**
   * Creates an ActivityMcpClient with custom options.
   *
   * <p><strong>Must be called from workflow code.</strong>
   *
   * @param timeout the activity start-to-close timeout
   * @param maxAttempts the maximum number of retry attempts
   * @return a new ActivityMcpClient
   */
  public static ActivityMcpClient create(Duration timeout, int maxAttempts) {
    McpClientActivity activity =
        Workflow.newActivityStub(
            McpClientActivity.class,
            ActivityOptions.newBuilder()
                .setStartToCloseTimeout(timeout)
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(maxAttempts).build())
                .build());
    return new ActivityMcpClient(activity);
  }

  /**
   * Gets the server capabilities for all connected MCP clients.
   *
   * <p>Results are cached after the first call.
   *
   * @return map of client name to server capabilities
   */
  public Map<String, McpSchema.ServerCapabilities> getServerCapabilities() {
    if (serverCapabilities == null) {
      serverCapabilities = activity.getServerCapabilities();
    }
    return serverCapabilities;
  }

  /**
   * Gets client info for all connected MCP clients.
   *
   * <p>Results are cached after the first call.
   *
   * @return map of client name to client implementation info
   */
  public Map<String, McpSchema.Implementation> getClientInfo() {
    if (clientInfo == null) {
      clientInfo = activity.getClientInfo();
    }
    return clientInfo;
  }

  /**
   * Calls a tool on a specific MCP client.
   *
   * @param clientName the name of the MCP client
   * @param request the tool call request
   * @return the tool call result
   */
  public McpSchema.CallToolResult callTool(String clientName, McpSchema.CallToolRequest request) {
    return activity.callTool(clientName, request);
  }

  /**
   * Lists all available tools from all connected MCP clients.
   *
   * @return map of client name to list of tools
   */
  public Map<String, McpSchema.ListToolsResult> listTools() {
    return activity.listTools();
  }
}
