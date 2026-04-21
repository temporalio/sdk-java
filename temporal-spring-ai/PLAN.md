# Plan: Per-model activity options (timeouts, retries)

## Scope

Today all `ActivityChatModel` instances share a single
`DEFAULT_TIMEOUT = Duration.ofMinutes(2)` and `DEFAULT_MAX_ATTEMPTS = 3`.
With multiple chat models registered via `SpringAiPlugin` (e.g. a fast 4o-mini
and a slow reasoning model), one timeout does not fit both. The integration
guide explicitly calls out: *"you may wish to specify a different
`start_to_close_timeout` depending on the model, and for example, whether it
is in thinking mode or not."*

## Design

Introduce a per-model options registry on `SpringAiPlugin` that
`ActivityChatModel.forModel(name)` consults when building its stub.

Key choice: the options are registered **on the plugin** (worker-side) but
must be resolved **in the workflow** when `Workflow.newActivityStub(...)` is
called. Because the plugin is a worker-side object, we either need to:

- (A) Ship the options map across the serialization boundary — awkward,
  `ActivityOptions` isn't trivially serializable.
- (B) Expose a registry lookup on the plugin via a static accessor that
  `ActivityChatModel.forModel` calls. Workers run both workflow and plugin in
  the same JVM, and `ActivityChatModel.forModel` is already called from
  workflow code — it can safely read a static registry populated at plugin
  construction time.

Go with (B). Plugin construction happens before any workflow executes on the
worker, so the registry is populated in time.

## Files to change

- `src/main/java/io/temporal/springai/plugin/SpringAiPlugin.java`
  - Accept an optional `Map<String, ActivityOptions> perModelOptions` on a
    new constructor / builder.
  - On construction, publish the map to a package-private static
    `SpringAiPluginOptions.register(map)` (or similar). Clearing on plugin
    shutdown is nice-to-have but not strictly required for a worker lifecycle.

- New: `src/main/java/io/temporal/springai/plugin/SpringAiPluginOptions.java`
  - Thread-safe registry: `register(Map)`, `optionsFor(String modelName)`,
    returns `Optional<ActivityOptions>`.

- `src/main/java/io/temporal/springai/model/ActivityChatModel.java`
  - `forModel(String modelName)` checks `SpringAiPluginOptions.optionsFor(
    modelName)`. If present, use those. Otherwise fall back to the existing
    default (2 min, 3 attempts).
  - Caller-supplied `ActivityOptions` (from the overload added in the
    `spring-ai/retry-and-options` branch) always wins over the registry.

- `src/main/java/io/temporal/springai/autoconfigure/SpringAiTemporalAutoConfiguration.java`
  - Accept a `Map<String, ActivityOptions>` bean if present
    (`ObjectProvider`) and forward it to the plugin constructor.

## Config example (documented in the PR body)

```java
@Bean
Map<String, ActivityOptions> springAiActivityOptions() {
    return Map.of(
        "reasoning", ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofMinutes(15))
            .setHeartbeatTimeout(Duration.ofMinutes(1))
            .build(),
        "fast", ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(20))
            .build());
}
```

## Coordination with other branches

This branch layers on top of `spring-ai/retry-and-options`, which adds the
`forModel(name, ActivityOptions)` overload. If that branch merges first,
this one depends on the new overload. If this branch merges first, implement
the overload here and the retry branch will rebase cleanly.

## Test plan

- Unit test: register a plugin with `perModelOptions = {"slow": 10min}`,
  build an `ActivityChatModel.forModel("slow")` in a workflow, and assert
  (via test env history inspection) the scheduled activity's
  `startToCloseTimeout` is 10 min, not 2 min.
- Negative: `forModel("unknown-name")` falls back to default 2 min.
- Explicit override: caller passes `ActivityOptions` — registry is ignored.

## PR

**Title:** `temporal-spring-ai: per-model ActivityOptions registry`

**Body:**

```
## What was changed
- `SpringAiPlugin` accepts an optional `Map<String, ActivityOptions>`
  keyed by chat-model name.
- `ActivityChatModel.forModel(name)` consults that registry; an
  explicit `ActivityOptions` argument still takes precedence.
- Auto-configuration forwards a user-provided
  `Map<String, ActivityOptions>` bean into the plugin.

## Why?
A single default (2 min start-to-close, 3 attempts) doesn't fit every
model. Reasoning and thinking-mode models routinely need 10+ minutes;
fast models want shorter timeouts so retries recover quickly.
The Temporal AI integration guide specifically calls this out as a
capability partners should expose. With this change, users register
one bean and never have to hand-build activity stubs again.
```
