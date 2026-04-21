# Plan: Provider-specific options pass-through

## Scope

`ChatModelTypes.ModelOptions` only covers the common Spring AI
`ChatOptions` fields (model, temperature, maxTokens, topP, topK, frequency
& presence penalty, stop sequences). Anything provider-specific —
OpenAI `reasoning_effort`, Anthropic thinking budget, structured-output
response format, seed, tools choice strategy, etc. — is dropped when
crossing the activity boundary.

This branch adds a generic pass-through for provider-specific options so
users don't have to fork the plugin to use features their model supports.

## Design choice

Two viable approaches:

1. **Opaque map** — add
   `Map<String, Object> providerOptions` to
   `ChatModelTypes.ModelOptions`. On the activity side, merge it into the
   provider-specific options builder via reflection or a
   `BiFunction<Builder, Map, Builder>` resolver.
2. **Serialized options blob** — carry the provider's own options class
   name + JSON payload; on the activity side, `ObjectMapper.readValue`
   into that class and pass it to `Prompt.builder().chatOptions(...)`.

Go with (1). It's simpler, avoids dragging provider classes into the
workflow's classpath, and keeps serialization uniform (Jackson handles
`Map<String, Object>` as long as values are JSON-serializable).

A small risk: provider builders expose these as typed setters, not as a
generic map. We'll handle a short allow-list of known keys for the most
common providers (OpenAI, Anthropic) in the activity, and for anything
else, try `ToolCallingChatOptions` / `ChatOptions` via a `ChatOptions`
`toMap`/`merge` helper if one exists; otherwise fall back to reflection
on the builder. Document which providers are first-class.

## Files to change

- `src/main/java/io/temporal/springai/model/ChatModelTypes.java`
  - Add `Map<String, Object> providerOptions` field to `ModelOptions`
    (nullable, JSON-ignored when null).

- `src/main/java/io/temporal/springai/model/ActivityChatModel.java`
  - When building `ModelOptions`, populate `providerOptions` by calling a
    small helper that inspects `prompt.getOptions()` for provider-specific
    subtypes (e.g. `OpenAiChatOptions`, `AnthropicChatOptions`) via
    reflection-free `instanceof` checks guarded by `ClassUtils.isPresent`.
    For unknown option types, best-effort: read `ChatOptions` common
    getters only and leave `providerOptions` null.

- `src/main/java/io/temporal/springai/activity/ChatModelActivityImpl.java`
  - In `createPrompt`, after the common `ToolCallingChatOptions.Builder`
    configuration, apply `providerOptions` via a provider-specific path
    chosen by inspecting the resolved `ChatModel` (or a config on the
    plugin). Keep the existing common-options path as a baseline.

- `src/main/java/io/temporal/springai/util/ProviderOptionsSupport.java` (new)
  - Utility with two methods:
    - `extract(ChatOptions opts) -> Map<String, Object>`
    - `apply(Map<String, Object> providerOptions, ChatOptions.Builder<?> b)`
  - Implementation uses `ClassUtils.isPresent` guards so users who don't
    have the optional provider on the classpath don't trigger
    `ClassNotFoundException`.

## Test plan

- Unit test with a fake `ChatOptions` subclass carrying a custom
  `reasoningEffort` field: verify the value survives the round-trip
  through `ActivityChatModel -> ChatModelTypes -> ChatModelActivityImpl`
  and is applied to the rebuilt prompt.
- Unit test for unknown provider options type: pass through without error,
  common fields still work.
- `WorkflowReplayer` replay check.

## PR

**Title:** `temporal-spring-ai: pass provider-specific ChatOptions through the activity boundary`

**Body:**

```
## What was changed
- `ChatModelTypes.ModelOptions` now carries an optional
  `providerOptions` map.
- `ActivityChatModel` extracts provider-specific fields
  (OpenAI/Anthropic, extensible) into that map when serializing the
  prompt.
- `ChatModelActivityImpl` applies the map back onto the provider's
  options builder before calling the model.
- Fields known to have zero behavioral meaning on the target model
  (e.g. OpenAI-only keys when Anthropic is selected) are ignored with a
  debug log, not an error.

## Why?
The previous implementation limited users to the common Spring AI
`ChatOptions` subset. Anyone needing OpenAI reasoning_effort,
Anthropic thinking budget, structured-output schemas, or any other
provider-specific knob had to fork the plugin. With this change those
options survive the round trip; users can configure their models the
same way they would outside Temporal.
```
