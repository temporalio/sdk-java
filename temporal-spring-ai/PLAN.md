# Plan: Preserve ChatResponse metadata (usage + rate limit)

## Scope

`ActivityChatModelImpl` already copies `ChatResponseMetadata.model`,
`RateLimit`, and `Usage` into the activity output record
(`ChatModelActivityOutput.ChatResponseMetadata`). But on the workflow side,
`ActivityChatModel.toResponse` only rehydrates the model name:

```java
builder.metadata(ChatResponseMetadata.builder().model(output.metadata().model()).build());
```

`Usage` and `RateLimit` are silently dropped. Observability tools,
Spring AI advisors, and any user code reading `.getMetadata().getUsage()` see
nothing. Pure bugfix — restore the round-trip.

## Files to change

- `src/main/java/io/temporal/springai/model/ActivityChatModel.java`
  - In `toResponse`, when `output.metadata()` is non-null, set `usage` and
    `rateLimit` on the builder too. Spring AI's `ChatResponseMetadata.Builder`
    exposes `.usage(Usage)` and `.rateLimit(RateLimit)`. Build those from the
    activity output's `Usage` / `RateLimit` records.
  - Handle the null sub-fields (`rateLimit == null`, `usage == null`) without
    crashing.

- No changes to `ChatModelTypes` (records already carry the fields) or
  `ChatModelActivityImpl` (already populates them).

## Test plan

- Unit test in the existing `WorkflowDeterminismTest` style: register a stub
  `ChatModel` that returns a `ChatResponse` whose metadata carries a known
  `Usage(10, 20, 30)`, run a workflow that calls it, and assert
  `response.getMetadata().getUsage().getTotalTokens() == 30` on the result
  path. Do the same for `RateLimit`.
- `WorkflowReplayer` replay check — nothing about history should change.

## PR

**Title:** `temporal-spring-ai: preserve Usage and RateLimit in ChatResponse metadata`

**Body:**

```
## What was changed
`ActivityChatModel.toResponse` now rehydrates `Usage` and `RateLimit` on the
`ChatResponseMetadata` it returns to workflow code, not just the model name.
The activity side already serialized these; they were being discarded on the
workflow side.

## Why?
Without this, users of the plugin can't read token usage or rate-limit
headers from Spring AI responses even though the underlying ChatModel
returned them. This breaks cost tracking, observability integrations, and
rate-limit-aware advisors. The fix is purely additive — the activity payload
already carried the data.
```
