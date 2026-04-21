# Plan: Side-effect replay tests

## Scope

The existing `WorkflowDeterminismTest` runs a workflow, fetches its history,
and calls `WorkflowReplayer.replayWorkflowExecution` — this catches
non-determinism but does **not** verify that plugin-managed side effects
(ChatModel calls, tool methods, MCP calls, `@SideEffectTool` bodies) are not
re-executed on replay. The Temporal AI partner review standards specifically
call out "tests that make sure you're avoiding repeated side effects on
replays."

This branch adds counting/assertion tests for each replayable surface the
plugin introduces.

## Files to change / add

- `src/test/java/io/temporal/springai/replay/ChatModelSideEffectTest.java`
  - Register a `ChatModel` that increments an `AtomicInteger` on every call.
  - Drive a workflow that calls it once, capture the history, then replay
    with `WorkflowReplayer`. Assert the counter is still 1 after replay.

- `src/test/java/io/temporal/springai/replay/ActivityToolSideEffectTest.java`
  - Build an `@ActivityInterface` whose `@Tool`-annotated method increments
    a counter on the activity implementation.
  - Use `ToolCallingStubChatModel`-style stub to produce a tool call, let
    the workflow drive the tool via `ActivityToolCallback`. Replay and
    assert the tool method counter is still 1.

- `src/test/java/io/temporal/springai/replay/SideEffectToolReplayTest.java`
  - Register a `@SideEffectTool` whose `@Tool` body increments a counter.
  - Drive the workflow to call it via the stubbed model. Capture history.
  - Replay and assert the inner body's counter did not advance
    (Workflow.sideEffect is memoized, so this is the behavior we're
    asserting).

- `src/test/java/io/temporal/springai/replay/McpToolSideEffectTest.java`
  - Register a fake `McpSyncClient` (or mock) that increments a counter on
    `callTool`. Replay and assert it stays at 1.
  - If mocking `McpSyncClient` is too heavy, skip this and cover MCP at the
    activity level (counter on `McpClientActivityImpl.callTool`).

All four tests share the pattern from `WorkflowDeterminismTest`: start
`TestWorkflowEnvironment`, register the counting impl, run, fetch history,
call `WorkflowReplayer.replayWorkflowExecution`, then
`assertEquals(1, counter.get())`.

## Test plan

- `./gradlew :temporal-spring-ai:test` locally and in CI.
- Verify the new tests actually fail if we regress (remove the activity
  memoization intentionally in a scratch branch to confirm the counter
  asserts catch it).

## PR

**Title:** `temporal-spring-ai: add side-effect replay tests for chat, tools, SideEffectTool, and MCP`

**Body:**

```
## What was changed
Four new replay tests under `src/test/.../replay/`:
- ChatModelSideEffectTest
- ActivityToolSideEffectTest
- SideEffectToolReplayTest
- McpToolSideEffectTest

Each one runs a workflow that drives the corresponding surface, captures
history, calls `WorkflowReplayer.replayWorkflowExecution`, and asserts that
the underlying side effect (counter increment) did not run a second time
during replay.

## Why?
The existing determinism test catches history-vs-command mismatches but
doesn't prove the plugin isn't re-invoking user side effects on replay.
Temporal's AI partner review standards require side-effect safety tests,
and these regressions would be easy to introduce accidentally if someone
later added an in-workflow fallback or a cache path. Counter-based tests
make any such regression show up immediately.
```
