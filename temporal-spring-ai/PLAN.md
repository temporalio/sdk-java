# Plan: README expansion + naming tidy

## Scope

Two small, user-facing cleanups that don't fit any of the other branches.

### 1. Consolidate the "default" model-name constant

`SpringAiPlugin.DEFAULT_MODEL_NAME = "default"` exists, but
`ChatModelActivityImpl` hard-codes the literal `"default"` in its
single-arg constructor:

```java
public ChatModelActivityImpl(ChatModel chatModel) {
    this.chatModels = Map.of("default", chatModel);
    this.defaultModelName = "default";
}
```

Replace the two string literals with `SpringAiPlugin.DEFAULT_MODEL_NAME`.
If introducing a cyclic dependency is a concern (activity → plugin), move
the constant to `ChatModelTypes` instead and have both reference it there.

### 2. README expansion

Current README covers quick-start and tool types. Add sections for:

- **Migrating from plain Spring AI** — a side-by-side showing a
  controller/service that calls `ChatClient.builder(chatModel)` and the
  same code inside a `@WorkflowInit` using
  `TemporalChatClient.builder(ActivityChatModel.forDefault())`. Emphasize
  the integration guide's "easy migration" goal.
- **Error handling** — brief note that chat and MCP activities have
  default retry policies; how to override via the `ActivityOptions`
  overload (added in `spring-ai/retry-and-options`); which Spring AI
  exceptions are classified non-retryable by default.
- **Known limitations**
  - `ChatClient.stream(...)` is not supported.
  - `defaultToolContext` is not supported (already documented inline).
  - Media `byte[]` is size-limited — prefer URI-based media (see
    `spring-ai/media-size-guard`).
  - Child workflow stubs aren't supported as tools (already noted in
    `TemporalToolUtil` but not in README).
- **Observability** — pointer to Temporal's OpenTelemetry interceptor
  docs and note that `TemporalChatClient.builder(model, registry, conv)`
  accepts an `ObservationRegistry` for Spring AI-side metrics.

## Files to change

- `src/main/java/io/temporal/springai/activity/ChatModelActivityImpl.java`
  - Replace `"default"` literals with a shared constant.
- (Optional) `src/main/java/io/temporal/springai/model/ChatModelTypes.java`
  - Host the constant if moving it out of `SpringAiPlugin` to break a
    cycle.
- `README.md`
  - Add the four new sections above.

## Test plan

- No behavior change — existing tests should continue to pass.
- Spot-check the README changes render correctly in the GitHub preview.

## PR

**Title:** `temporal-spring-ai: README expansion and default-model-name cleanup`

**Body:**

```
## What was changed
- Replaced hard-coded `"default"` model-name literal in
  `ChatModelActivityImpl` with a shared constant.
- Expanded README with four new sections: migrating from plain
  Spring AI, error handling / retry overrides, known limitations,
  and observability pointers.

## Why?
The magic string had already drifted once (new constant lived in
`SpringAiPlugin` but the activity kept its own copy) — consolidating
prevents the next drift. The README covered quick-start but left
adopters guessing about migration steps, failure modes, and what
isn't supported; each section here answers a question we've seen or
anticipate from early users.
```
