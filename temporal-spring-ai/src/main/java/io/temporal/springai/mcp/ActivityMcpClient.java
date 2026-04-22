package io.temporal.springai.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

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
  private final Optional<ActivityOptions> baseOptions;
  private Map<String, McpSchema.ServerCapabilities> serverCapabilities;
  private Map<String, McpSchema.Implementation> clientInfo;

  /**
   * Creates a new ActivityMcpClient with the given activity stub.
   *
   * @param activity the activity stub for MCP operations
   */
  public ActivityMcpClient(McpClientActivity activity) {
    this(activity, Optional.empty());
  }

  /**
   * Creates a new ActivityMcpClient. When {@code baseOptions} is present, {@link #callTool(String,
   * McpSchema.CallToolRequest, String)} rebuilds the activity stub with a per-call Summary on top
   * of those options. When empty, the caller supplied a pre-built stub whose options we don't know,
   * so we call through it as-is and drop any requested summary.
   */
  private ActivityMcpClient(McpClientActivity activity, Optional<ActivityOptions> baseOptions) {
    this.activity = activity;
    this.baseOptions = baseOptions;
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
    ActivityOptions options =
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(timeout)
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(maxAttempts).build())
            .build();
    McpClientActivity activity = Workflow.newActivityStub(McpClientActivity.class, options);
    return new ActivityMcpClient(activity, Optional.of(options));
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
    return callTool(clientName, request, Optional.empty());
  }

  /**
   * Calls a tool on a specific MCP client, attaching the given activity Summary to the scheduled
   * activity so it renders meaningfully in the Temporal UI. Falls back to the base stub when no
   * {@link ActivityOptions} are known (e.g. when this client was constructed from a user-supplied
   * stub rather than one of the {@link #create} factories).
   *
   * @param clientName the name of the MCP client
   * @param request the tool call request
   * @param summary the activity Summary, or empty to omit
   * @return the tool call result
   */
  public McpSchema.CallToolResult callTool(
      String clientName, McpSchema.CallToolRequest request, Optional<String> summary) {
    // Overlay the summary onto a fresh stub only when both a summary is requested AND we have
    // a recipe to rebuild the stub from (baseOptions). If either is missing, fall through to
    // the cached activity — it already has baseOptions baked in if we knew them at construction.
    if (summary.isPresent() && baseOptions.isPresent()) {
      McpClientActivity stub =
          Workflow.newActivityStub(
              McpClientActivity.class,
              ActivityOptions.newBuilder(baseOptions.get()).setSummary(summary.get()).build());
      return stub.callTool(clientName, request);
    }
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
