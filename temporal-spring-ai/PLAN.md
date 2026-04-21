# Plan: Retry classification + ActivityOptions acceptance

## Scope

Two related production-readiness gaps:

1. `ActivityChatModel.forDefault()` / `forModel(name)` hard-code
   `maxAttempts=3` with no `nonRetryableErrorTypes`. A bad API key, 400 Bad
   Request, or invalid-prompt error churns through retries + exponential
   backoff before failing. `ActivityMcpClient.create()` has the same issue.
2. The factory API only lets users override timeout + maxAttempts. There's
   no way to pass heartbeats, a specific task queue, priorities,
   `doNotRetry`, or a custom Summary. Users who need any of these today
   have to bypass the factories entirely and construct their own
   `Workflow.newActivityStub(ChatModelActivity.class, ...)`.

### Interaction with the `spring-ai/activity-summaries` branch

The summaries branch landed a plugin-owned `ActivityOptions` store on
`ActivityChatModel` and `ActivityMcpClient`: the factories (`forModel`,
`create`) now retain the options they built the stub with, and the per-call
path overlays a Summary on top. The bare public constructors
(`new ActivityChatModel(stub)`, `new ActivityMcpClient(activity)`) receive
no options and therefore emit no summary — silently. That's a real UX wart:
anyone who rolls their own stub today loses Summary labels on the chat /
MCP rows in the Temporal UI.

The `ActivityOptions` factory overloads this branch adds **are the proper
fix** for that wart. Users who need non-default timeouts / retries / task
queue / heartbeats today fall back to the public constructor to get those;
giving them a factory that takes `ActivityOptions` means they keep
summaries while still customizing the stub. This branch should therefore:

- Explicitly document (in `ActivityChatModel` and `ActivityMcpClient`
  javadocs, and the README) that Summary labels appear only when the stub
  is built via one of the factories, and point at the new overloads as the
  recommended path for customization.
- Leave the public constructors in place for backward compatibility, but
  mark them `@Deprecated` or, at minimum, add a javadoc note that they
  skip UI summaries. Decision: ship the deprecation alongside the new
  factory so the doc and the warning point to the same replacement.

## Files to change

- `src/main/java/io/temporal/springai/model/ActivityChatModel.java`
  - Add `forDefault(ActivityOptions options)` and
    `forModel(String modelName, ActivityOptions options)` overloads.
  - Change the existing `forModel(modelName, timeout, maxAttempts)` to build
    an `ActivityOptions` internally that includes a default
    `RetryOptions.setDoNotRetry(...)` list (see §Retry classification below)
    and delegate to the new `ActivityOptions` overload.
  - The new overloads pass their `ActivityOptions` into the existing
    private `(stub, modelName, baseOptions)` constructor added by the
    summaries branch, so Summary labels work out of the box — no extra
    wiring.
  - Javadoc on the public `ActivityChatModel(ChatModelActivity[, String])`
    constructors: add a note that callers who want UI Summaries should
    prefer the new `forDefault(ActivityOptions)` / `forModel(String,
    ActivityOptions)` factories. Mark the constructors `@Deprecated` with
    a message pointing at the factory replacement.
  - Keep all existing overloads working at runtime — purely additive API.

- `src/main/java/io/temporal/springai/mcp/ActivityMcpClient.java`
  - Same treatment: add `create(ActivityOptions options)` overload; existing
    `create(timeout, maxAttempts)` delegates.
  - Thread the passed `ActivityOptions` through the private
    `(activity, baseOptions)` constructor added by the summaries branch so
    `callTool(..., summary)` overlays work.
  - `@Deprecated` the public `new ActivityMcpClient(activity)` constructor
    with a javadoc note recommending the factory for Summary support.

- `README.md`
  - Brief note in the quick-start and the "Tool Types" section that chat
    and MCP rows in the Temporal UI are labeled with Summaries when (and
    only when) built via the factories.

### Retry classification

Default `doNotRetry` list for chat model activities should cover clearly
permanent failures from Spring AI. Candidates (need to verify exact FQCNs
against spring-ai 1.1):

- `org.springframework.ai.retry.NonTransientAiException`
- `java.lang.IllegalArgumentException` (e.g. unknown model name from
  `ChatModelActivityImpl.resolveChatModel`)

Do **not** add `RuntimeException` or broad superclasses; the default Temporal
behavior should still retry on network errors, 5xx, rate-limits, timeouts.

MCP activities: add `IllegalArgumentException` (client-not-found is already
thrown as `ApplicationFailure` with `nonRetryable=true`, which wins on its
own, so no extra entries strictly required).

## Test plan

- Unit test: pass an `ActivityOptions` with a custom `taskQueue` and a custom
  `doNotRetry` entry; verify (via test env history inspection) the activity
  was scheduled on that task queue and that a thrown
  `NonTransientAiException` does not trigger retry.
- Unit test: default factory still retries on a transient `RuntimeException`
  (ensure the doNotRetry list doesn't over-reach).
- Extend `ActivitySummaryTest` (added on the summaries branch) to assert a
  workflow built via `ActivityChatModel.forDefault(customOptions)` still
  carries the expected `chat: …` summary on its scheduled chat activity.
  This guards against a regression where the new overloads silently drop
  the `baseOptions` wiring.
- `WorkflowReplayer` replay check.

## PR

**Title:** `temporal-spring-ai: accept ActivityOptions and classify non-retryable AI errors`

**Body:**

```
## What was changed
- `ActivityChatModel.forDefault(ActivityOptions)` and
  `ActivityChatModel.forModel(String, ActivityOptions)` overloads added.
- `ActivityMcpClient.create(ActivityOptions)` overload added.
- Default RetryOptions for chat and MCP activities now include
  `doNotRetry` entries for clearly non-transient Spring AI failures
  (`NonTransientAiException`, `IllegalArgumentException`).
- Public `new ActivityChatModel(...)` and `new ActivityMcpClient(...)`
  constructors are `@Deprecated` with javadoc pointing at the factory
  replacement. They still work at runtime but skip UI Summaries, which is
  now called out explicitly.
- Existing factory signatures are preserved; they delegate to the new
  `ActivityOptions` overloads.

## Why?
Previously the only way to customize the chat or MCP activity stub was
via `(timeout, maxAttempts)` — no heartbeats, task queue override,
priority, or `doNotRetry`. Users hitting those needs had to bypass the
factories and call the public constructor with a hand-built stub, which
as of the summaries branch silently drops the Temporal UI labels for
chat and MCP rows. The new `ActivityOptions` overloads close that gap:
users get full customization *and* keep the Summary labels.

Also: transient-vs-permanent classification matters a lot for LLM calls
(a 401 shouldn't retry for minutes), and the integration guide
specifically calls out "tell Temporal which errors are retryable and
which are not." This brings the plugin in line.
```
