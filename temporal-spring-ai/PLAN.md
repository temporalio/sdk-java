# Plan: Activity summaries for debuggability

## Scope

Temporal's AI integration guide requires activity calls to set a Summary so
the Temporal UI renders something more useful than a bare
`ChatModelActivity.callChatModel`. Today none of the activities the plugin
issues on behalf of a workflow set one. This branch adds summaries on every
activity stub the plugin produces.

## Files to change

- `src/main/java/io/temporal/springai/model/ActivityChatModel.java`
  - Before calling the activity, build a summary like
    `"chat: " + modelName + " · " + truncate(lastUserMessage, 60)` and attach
    it via per-call options. Since `ChatModelActivity` is a typed stub, either
    rebuild the stub with `ActivityOptions.newBuilder(existing).setSummary(...)`
    per call, or invoke via `ActivityStub.fromTyped(stub)` with per-call
    `ActivityOptions` so the summary reflects the current prompt.
  - Fall back to `"chat: " + modelName` if the prompt has no user text.

- `src/main/java/io/temporal/springai/tool/ActivityToolCallback.java`
  - Today `call(String toolInput)` delegates directly to the underlying
    `MethodToolCallback`, which invokes the activity. Attach a summary of the
    form `"tool: " + toolDefinition.name()` before delegation. Simplest path:
    bypass `MethodToolCallback` and invoke via `ActivityStub.fromTyped(...)`
    with per-call options including summary.

- `src/main/java/io/temporal/springai/mcp/McpToolCallback.java`
  - In `call(String toolInput)`, before calling `client.callTool(...)`,
    attach a summary `"mcp: " + clientName + "." + tool.name()`. Plumb this
    through `ActivityMcpClient` so it either exposes
    `callTool(clientName, request, summary)` or accepts per-call options.

- `src/main/java/io/temporal/springai/mcp/ActivityMcpClient.java`
  - Add the summary overload; keep the current one for backward compat.

## Test plan

- Add `ActivitySummaryTest` (or extend `WorkflowDeterminismTest`) that runs a
  workflow, fetches history via `client.fetchHistory(...)`, and asserts
  `ActivityTaskScheduledEventAttributes.userMetadata.summary` for chat and
  tool activities contains the expected strings.
- Replay via `WorkflowReplayer` to confirm no determinism regression.

## PR

**Title:** `temporal-spring-ai: attach activity summaries for chat, tools, and MCP calls`

**Body:**

```
## What was changed
- Chat model activity calls now carry a Summary of the form
  `chat: <model> · <first 60 chars of user prompt>`.
- Activity-backed tool calls carry a Summary of the form
  `tool: <toolName>`.
- MCP tool calls carry a Summary of the form
  `mcp: <clientName>.<toolName>`.
- `ActivityMcpClient` gains a `callTool(clientName, request, summary)`
  overload; prior signature is preserved.

## Why?
The Temporal UI renders activity summaries prominently in workflow timelines.
Without them, every chat/tool/MCP call in a Spring AI workflow shows up as an
opaque `ChatModelActivity.callChatModel` row, making it painful to debug
multi-step agents. This matches the debuggability guidance in Temporal's AI
partner integration guide and the behavior of the Python/TypeScript plugins.
```
