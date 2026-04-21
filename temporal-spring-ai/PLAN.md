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

## Files to change

- `src/main/java/io/temporal/springai/model/ActivityChatModel.java`
  - Add `forDefault(ActivityOptions options)` and
    `forModel(String modelName, ActivityOptions options)` overloads.
  - Change the existing `forModel(modelName, timeout, maxAttempts)` to build
    an `ActivityOptions` internally that includes a default
    `RetryOptions.setDoNotRetry(...)` list (see §Retry classification below)
    and delegate to the new `ActivityOptions` overload.
  - Keep all existing overloads working — purely additive API.

- `src/main/java/io/temporal/springai/mcp/ActivityMcpClient.java`
  - Same treatment: add `create(ActivityOptions options)` overload; existing
    `create(timeout, maxAttempts)` delegates.

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
- Existing factory signatures are preserved; they now delegate to the new
  `ActivityOptions` overloads.

## Why?
Previously the only way to customize the chat or MCP activity stub was via
`(timeout, maxAttempts)` — no heartbeats, task queue override, Summary,
priority, or `doNotRetry`. Users hitting those needs had to bypass the
factories entirely. Also: transient-vs-permanent classification matters a
lot for LLM calls (a 401 shouldn't retry for minutes), and the integration
guide specifically calls out "tell Temporal which errors are retryable and
which are not." This brings the plugin in line.
```
