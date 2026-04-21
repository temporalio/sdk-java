# Plan: Activity summaries for debuggability

## Scope

Temporal's AI integration guide recommends setting a Summary on scheduled
activities so the Temporal UI can render something more useful than a bare
`ChatModelActivity.callChatModel`. The Java SDK exposes this via
`ActivityOptions.Builder.setSummary(String)`; the summary is baked into the
stub at creation time.

This branch adds summaries **only on the activity calls the plugin itself
creates stubs for** — chat model and MCP. Cases where the *user* creates the
stub and hands it to the plugin are deliberately out of scope.

### Why chat and MCP only

- **Chat model**: `ActivityChatModel.forModel(...)` builds the stub, so the
  plugin knows (and owns) the `ActivityOptions`. Adding a summary is a
  one-line overlay before calling the activity.
- **MCP**: `ActivityMcpClient.create(...)` builds the stub, same story.

### Explicitly out of scope

- **Activity-backed tools** (`ActivityToolCallback`): the user creates the
  activity stub in their workflow (`Workflow.newActivityStub(...)`) and hands
  it to `.defaultTools(...)`. The stub's `ActivityOptions` are sealed inside
  the proxy, and there is no public API to override options per call.
  Reaching in reflectively is brittle and was rejected during review.
  Scheduled activities for tool calls keep their default
  (activity-type-name) row in the UI.
- **Nexus tools** (`NexusToolCallback`): same shape as activity tools — user
  owns the stub. Skip for the same reason.
- **`@SideEffectTool`** (`SideEffectToolCallback`): runs via
  `Workflow.sideEffect(...)`, which records a `MarkerRecorded` event, not an
  `ActivityTaskScheduled` event. `MarkerRecorded` has no user-facing Summary
  field, so there is nothing to attach. Skip.

## Files to change

- `src/main/java/io/temporal/springai/model/ActivityChatModel.java`
  - Keep the existing stub but also retain the `ActivityOptions` used to
    build it (new private field). When `ActivityOptions` are known, each
    call rebuilds the stub via
    `ActivityOptions.newBuilder(base).setSummary(s).build()`. When the bare
    public constructor is used (user-supplied stub, options unknown), fall
    back to the cached stub — no summary, no reflection.
  - Summary format: `"chat: " + modelName + " · " + truncate(lastUserText, 60)`
    with newlines replaced by spaces; `"chat: " + modelName` when there is no
    user text.

- `src/main/java/io/temporal/springai/mcp/ActivityMcpClient.java`
  - Same pattern: store the `ActivityOptions` used by `create(...)`. Add a
    `callTool(clientName, request, summary)` overload; the existing
    `callTool(clientName, request)` delegates to it with a null summary.

- `src/main/java/io/temporal/springai/mcp/McpToolCallback.java`
  - `call(toolInput)` builds `"mcp: " + clientName + "." + tool.name()` and
    passes it to the new overload.

No changes to `TemporalStubUtil`, `ActivityToolCallback`, `ActivityToolUtil`,
`NexusToolCallback`, or `SideEffectToolCallback`.

## Test plan

- Add `ActivitySummaryTest` under `src/test/...`:
  - Run a workflow that uses `ActivityChatModel.forDefault()` and triggers a
    single chat call. Fetch history via `client.fetchHistory(...)` and
    assert the `ActivityTaskScheduled` event's `userMetadata.summary`
    decodes to a string starting with `"chat: default"`.
  - Same pattern for MCP: use a fake `McpSyncClient` that returns a known
    tool; the workflow drives a tool call; assert the scheduled
    `McpClientActivity.callTool` activity has a summary matching
    `"mcp: <clientName>.<toolName>"`.
- Replay the captured history with `WorkflowReplayer.replayWorkflowExecution`
  in each test to confirm no determinism regression.

## PR

**Title:** `temporal-spring-ai: attach activity summaries for chat and MCP calls`

**Body:**

```
## What was changed
- `ActivityChatModel` attaches an activity Summary of the form
  `chat: <model> · <truncated user prompt>` to each chat-model activity call.
- `ActivityMcpClient` gains a `callTool(clientName, request, summary)`
  overload; `McpToolCallback` uses it to attach `mcp: <client>.<tool>`.
- Summaries are applied only when the plugin itself created the activity
  stub (via the existing `forModel`/`create` factories). When the user
  supplies their own pre-built stub, behavior is unchanged.

Activity-backed tool calls, Nexus tool calls, and `@SideEffectTool` calls
are intentionally out of scope — either the stub is user-owned (activity
and Nexus tools) or there is no Summary slot on the resulting history event
(`@SideEffectTool` writes a `MarkerRecorded`, not an
`ActivityTaskScheduled`).

## Why?
Without Summaries, every chat and MCP step in a Spring AI workflow shows up
as an opaque `callChatModel` or `callTool` row in the Temporal UI, making
multi-step agents hard to follow. Chat and MCP are the cases where the
plugin owns stub creation end-to-end, so adding the Summary is a cheap,
low-risk overlay; broader coverage would require reaching into user-owned
stubs and was deliberately left out to keep this change small and
review-friendly.
```
