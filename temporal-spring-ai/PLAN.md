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

1. **Opaque map.** Add `Map<String, Object> providerOptions` to
   `ChatModelTypes.ModelOptions`. On the activity side, merge it into a
   provider-specific options builder via per-provider allow-lists or
   reflection.
2. **Serialized options blob.** Carry the user's `ChatOptions` object as
   `(className, json)`. On the activity side, `Class.forName(className)`
   + `ObjectMapper.readValue(json, cls)` to rehydrate the exact subclass
   and hand it to `Prompt.builder().chatOptions(...)`.

**Going with (2).** Earlier revisions of this plan picked (1) on the
grounds that (2) "drags provider classes into the workflow's classpath,"
but that reasoning was wrong:

- Workflow and activity workers are commonly separate JVMs — the
  distributed-worker story is the whole point of Temporal.
- However, for the user to create a `OpenAiChatOptions` instance in
  their workflow at all, `spring-ai-openai` must be on the workflow
  worker's classpath. Meanwhile the activity worker has the same class
  because that's where the `OpenAiChatModel` bean lives and actually
  makes the HTTP call. So *in every scenario where this feature is
  actually used*, the provider class is present on both sides. Option
  (2) exploits that; option (1) doesn't avoid the constraint — it just
  makes the code more work.

Option (2) is strictly better here: full fidelity (every field, not
just the common subset), no per-provider allow-list to maintain, no
builder gymnastics, and the plugin module itself still carries no
provider-specific deps.

## Files to change

- `src/main/java/io/temporal/springai/model/ChatModelTypes.java`
  - Add two nullable fields to `ModelOptions`:
    - `@JsonProperty("chat_options_class") String chatOptionsClass`
    - `@JsonProperty("chat_options_json") String chatOptionsJson`
  - Both null means "no user-supplied `ChatOptions` subclass; use the
    common fields as before."

- `src/main/java/io/temporal/springai/model/ActivityChatModel.java`
  - In `createActivityInput`, when `prompt.getOptions()` is non-null,
    serialize it with Jackson (`ObjectMapper.writeValueAsString`) and
    record its `getClass().getName()`. Wrap in `try/catch` — if Jackson
    can't serialize the options, log at debug and leave the two new
    fields null so the activity side falls back to common fields.
  - Continue populating the common-field `ModelOptions` as today — the
    activity only uses them when the serialized blob is absent.

- `src/main/java/io/temporal/springai/activity/ChatModelActivityImpl.java`
  - In `createPrompt`, when both serialized fields are non-null:
    1. `Class<?> cls = Class.forName(chatOptionsClass);`
    2. `ChatOptions opts = (ChatOptions) objectMapper.readValue(json, cls);`
    3. Use `Prompt.builder().messages(messages).chatOptions(opts)` with
       `opts` as-is, then overlay tool callbacks on top (tools still
       need to be added as stubs since that logic is plugin-owned).
  - If either step throws (class not found, deserialization error),
    log a warning and fall back to the current common-field path.

- (No new utility class — the logic is small enough to live at the two
  call sites.)

### Fallback invariants

- Null `ChatOptions` on the workflow side: new fields null; activity
  uses common fields (existing behavior).
- Non-null `ChatOptions` but Jackson-unserializable: new fields null;
  activity uses common fields with a debug log.
- Non-null + serialized but class-not-found on the activity side: log
  a warning and use common fields.
- Non-null + serialized + class loads: use the deserialized options;
  the common `ModelOptions` fields are ignored on the activity side.

## Test plan

- `ProviderOptionsPassthroughTest`:
  - Define a test-local `ChatOptions` subclass with an extra field
    (e.g. `reasoningEffort`). Workflow builds a prompt with it, drives
    a chat call, and the stub `ChatModel` captures the `Prompt` it
    receives. Assert the received options is an instance of the
    test-local class and the extra field survived.
  - Variant: pass a plain `ToolCallingChatOptions` (no extras) —
    assert the activity still gets a non-null options object and the
    common fields (temperature, maxTokens) match.
- Negative: pass a `ChatOptions` whose class is on the workflow
  classpath but can't be loaded on the activity side (simulate by
  rewriting the `chatOptionsClass` to a bogus name before activity
  receives it — possible via a test-specific override of
  `ChatModelActivityImpl`). Assert the activity falls back cleanly and
  the chat still completes, using common fields.
- `WorkflowReplayer` replay check — nothing about history should
  change.

## PR

**Title:** `temporal-spring-ai: pass provider-specific ChatOptions through the activity boundary`

**Body:**

```
## What was changed
- `ChatModelTypes.ModelOptions` carries two new nullable fields,
  `chatOptionsClass` and `chatOptionsJson`, representing the caller's
  full `ChatOptions` as `(class name, JSON)`.
- `ActivityChatModel` serializes the caller's options with Jackson
  when a non-null `ChatOptions` is present; it continues to populate
  the common fields so older clients / missing-class fallbacks still
  work.
- `ChatModelActivityImpl` prefers the serialized blob when both
  fields are present and the class loads; otherwise it falls back to
  the common-field path.
- If serialization fails on the workflow side, or deserialization /
  class loading fails on the activity side, the plugin logs and falls
  back to the common-field path rather than failing the call.

## Why?
The previous implementation limited users to the common Spring AI
`ChatOptions` subset. Anyone needing OpenAI `reasoning_effort`,
Anthropic thinking budget, structured-output schemas, or any other
provider-specific knob had to fork the plugin. With this change those
options survive the round trip; users configure their models the same
way they would outside Temporal.

The precondition for this to work: both workers have the user's
`ChatOptions` subclass on their classpath. In practice this always
holds — the user can't construct `OpenAiChatOptions` in their
workflow without `spring-ai-openai` being present, and the activity
worker already has it because that's where the `OpenAiChatModel` bean
runs.
```
