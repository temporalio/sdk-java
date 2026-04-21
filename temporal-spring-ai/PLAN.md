# Plan: Media byte[] size guard

## Scope

`ChatModelTypes.MediaContent` has a `byte[] data` field. If a user attaches
an image-as-bytes to a `UserMessage`, those bytes end up serialized into:

1. The activity's input payload (ActivityTaskScheduled event).
2. The activity's result payload, if the assistant echoes media back.
3. Every `ToolResponseMessage` carrying media, on every tool iteration.

Temporal's default per-event payload size limit is 2 MiB, and total history
is bounded too. A single 5 MB PNG silently breaks the workflow at runtime
with a cryptic serialization error. The integration guide's
"arguments and return values must be serializable" point generalizes to
"don't stuff giant blobs into history."

This branch adds a defensive size check with a clear error message, plus
documentation steering users to URI-based media.

## Files to change

- `src/main/java/io/temporal/springai/model/ActivityChatModel.java`
  - In `toMediaContent(Media)`, when `media.getData() instanceof byte[]`,
    check the length against a threshold (default **1 MiB**, configurable).
    If exceeded, throw `IllegalArgumentException` with a message pointing
    the user at the URI-based `Media` constructor and the threshold.

- `src/main/java/io/temporal/springai/activity/ChatModelActivityImpl.java`
  - Same guard on the return path (`fromMedia` / `toOutput`).

- New constant `MAX_MEDIA_BYTES_IN_HISTORY = 1 * 1024 * 1024` on
  `ChatModelTypes` or `ActivityChatModel`.

- `README.md`
  - Add a "Media in messages" section that:
    - States raw `byte[]` media is size-limited.
    - Recommends passing `Media` via URI (`new Media(mimeType, URI)`) or
      via a binary store your activity writes to before the chat call, so
      only the URI crosses the history boundary.

## Threshold rationale

- Temporal default history-event payload limit is 2 MiB.
- A chat activity carries messages + tool definitions + options + media,
  and the result carries messages back; both events separately must fit.
- 1 MiB leaves headroom for everything else. Users who want to raise it
  can override via a system property or config — document that.

## Test plan

- Unit test: `UserMessage` with 2 MiB media → `IllegalArgumentException`
  with a message mentioning the limit and the URI alternative.
- Unit test: `UserMessage` with 500 KiB media → passes through.
- Unit test: assistant echoes media back → same guard applies on the
  result path.

## PR

**Title:** `temporal-spring-ai: guard large media byte[] from entering workflow history`

**Body:**

```
## What was changed
- `ActivityChatModel` and `ChatModelActivityImpl` now reject
  `Media.data` byte arrays larger than 1 MiB with a clear
  `IllegalArgumentException` pointing users to URI-based media.
- The threshold is a single named constant so teams can override it.
- README gains a short "Media in messages" section documenting the
  limit and the URI-based workaround.

## Why?
Raw media bytes in messages get serialized into every relevant history
event. Without a guard, a single large image silently produces a
`gRPC message exceeds maximum size` failure at workflow runtime,
which is hard for users to diagnose. Failing fast at the serialization
edge, with an actionable message, turns a mystery-failure into a
clear "use a URI" hint.
```
