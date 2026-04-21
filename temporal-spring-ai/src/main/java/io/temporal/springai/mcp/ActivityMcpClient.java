package io.temporal.springai.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

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

  /**
   * Error types that the default retry policy treats as non-retryable. {@link
   * IllegalArgumentException} covers unknown-client-name lookups. Client-not-found is already
   * thrown as an {@code ApplicationFailure} with {@code nonRetryable=true} and wins on its own.
   *
   * <p>Applied only to the factories that build {@link ActivityOptions} internally. When callers
   * pass their own {@link ActivityOptions} via {@link #create(ActivityOptions)}, their {@link
   * RetryOptions} are used verbatim.
   */
  public static final List<String> DEFAULT_NON_RETRYABLE_ERROR_TYPES =
      List.of("java.lang.IllegalArgumentException");

  private final McpClientActivity activity;
  private final ActivityOptions baseOptions;
  private Map<String, McpSchema.ServerCapabilities> serverCapabilities;
  private Map<String, McpSchema.Implementation> clientInfo;

  /** Use one of the {@link #create()} / {@link #create(ActivityOptions)} factories. */
  private ActivityMcpClient(McpClientActivity activity, ActivityOptions baseOptions) {
    this.activity = activity;
    this.baseOptions = baseOptions;
  }

  /**
   * Creates an ActivityMcpClient with the plugin's default {@link ActivityOptions} (30-second
   * start-to-close timeout, 3 attempts, {@link IllegalArgumentException} marked non-retryable).
   *
   * <p><strong>Must be called from workflow code.</strong>
   *
   * @return a new ActivityMcpClient
   */
  public static ActivityMcpClient create() {
    return create(defaultActivityOptions(DEFAULT_TIMEOUT, DEFAULT_MAX_ATTEMPTS));
  }

  /**
   * Creates an ActivityMcpClient with a custom timeout and attempt count. Other defaults
   * (non-retryable error classification) are preserved.
   *
   * <p><strong>Must be called from workflow code.</strong>
   *
   * @param timeout the activity start-to-close timeout
   * @param maxAttempts the maximum number of retry attempts
   * @return a new ActivityMcpClient
   */
  public static ActivityMcpClient create(Duration timeout, int maxAttempts) {
    return create(defaultActivityOptions(timeout, maxAttempts));
  }

  /**
   * Creates an ActivityMcpClient using the supplied {@link ActivityOptions}. Pass this when you
   * need a specific task queue, heartbeat, priority, or custom {@link RetryOptions}. The provided
   * options are used verbatim — the plugin does not augment the caller's {@link RetryOptions}.
   *
   * <p><strong>Must be called from workflow code.</strong>
   *
   * @param options the activity options to use for each MCP call
   * @return a new ActivityMcpClient
   */
  public static ActivityMcpClient create(ActivityOptions options) {
    McpClientActivity activity = Workflow.newActivityStub(McpClientActivity.class, options);
    return new ActivityMcpClient(activity, options);
  }

  /**
   * Returns the plugin's default {@link ActivityOptions} for MCP calls. Useful as a starting point
   * when you want to tweak a field without losing the sensible defaults:
   *
   * <pre>{@code
   * ActivityMcpClient.create(
   *     ActivityOptions.newBuilder(ActivityMcpClient.defaultActivityOptions())
   *         .setTaskQueue("mcp-heavy")
   *         .build());
   * }</pre>
   */
  public static ActivityOptions defaultActivityOptions() {
    return defaultActivityOptions(DEFAULT_TIMEOUT, DEFAULT_MAX_ATTEMPTS);
  }

  private static ActivityOptions defaultActivityOptions(Duration timeout, int maxAttempts) {
    return ActivityOptions.newBuilder()
        .setStartToCloseTimeout(timeout)
        .setRetryOptions(
            RetryOptions.newBuilder()
                .setMaximumAttempts(maxAttempts)
                .setDoNotRetry(DEFAULT_NON_RETRYABLE_ERROR_TYPES.toArray(new String[0]))
                .build())
        .build();
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
    return callTool(clientName, request, null);
  }

  /**
   * Calls a tool on a specific MCP client, attaching the given activity Summary to the scheduled
   * activity so it renders meaningfully in the Temporal UI.
   *
   * @param clientName the name of the MCP client
   * @param request the tool call request
   * @param summary the activity Summary, or null to omit
   * @return the tool call result
   */
  public McpSchema.CallToolResult callTool(
      String clientName, McpSchema.CallToolRequest request, @Nullable String summary) {
    if (summary == null) {
      return activity.callTool(clientName, request);
    }
    McpClientActivity stub =
        Workflow.newActivityStub(
            McpClientActivity.class,
            ActivityOptions.newBuilder(baseOptions).setSummary(summary).build());
    return stub.callTool(clientName, request);
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
