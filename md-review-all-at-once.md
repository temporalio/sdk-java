# Maciej's review of sdk-java#3013 — all fixes, applied at once

Record of the 14 inline review comments on
https://github.com/temporalio/sdk-java/pull/3013 and the change each one drove.
13 were actionable; one ("lol") needed nothing.

The Java fixes were applied together on branch `oc-md-review-all`, and are being
re-applied one commit per comment on `oc-md-review-pieces`.

---

## Java

**Comment A** — *`ActivityExecutionDescription.java:32`:* "`info` field is now redundant, remove it and change methods to use `response.getInfo()`"

Dropped `private final ActivityExecutionInfo info;`; all ~20 accessors now read `response.getInfo()`.

**Comment B** — *`ActivityExecutionDescription.java:57`:* "We should add `ActivitySerializationContext` to `dataConverter` here."

Constructor now stores `dataConverter.withContext(new ActivitySerializationContext(namespace, null, null, getActivityType(), getTaskQueue(), false))`. This also let the `namespace` field go, and removed the ad-hoc `withContext(...)` that `getStaticSummary`/`getStaticDetails` were each building inline.

**Comment C** — *`ActivityExecutionDescription.java:289`:* "`getInput` should have only one 0-arg overload that returns `EncodedValues`. After that change, we should also remove `getInputCount`. While we're at it, I think we should change `getHeartbeatDetails` to return `EncodedValues` too."

Five `getInput` overloads plus `getInputCount` collapse to `EncodedValues getInput()`. Both `getHeartbeatDetails` overloads collapse to `EncodedValues getHeartbeatDetails()`.

**Comment D** — *`ActivityExecutionDescription.java:350`:* "We should match what we do in `ActivityClient.startActivity`." (suggested `return getResult(valueType, null);`)

Applied; the two-arg form takes `@Nullable Type` and normalizes null to `valueType`, the same way `decodeOutcome` does.

**Comment E** — *`ActivityExecutionDescription.java:340`:* "Protobuf getters are null-coalescing. Fix other similar lines too." (suggested `return response.getOutcome().hasResult();`)

Applied to `hasResult()`; the "other similar lines" were the `!response.hasOutcome() ||` guard in the outcome-failure getter and the ternary in the deleted `getInputCount`.

**Comment F** — *`ActivityExecutionDescription.java:376`:* "I'd rename this method to `getOutcomeFailure` or something to better differentiate it from `getLastFailure`. We should also change return type of both this and `getLastFailure` to `RuntimeException`."

`getFailure` → `getOutcomeFailure`; both it and `getLastFailure` now return `RuntimeException` (free — `DataConverter.failureToException` already returns that).

**Comment G** — *`ActivityExecutionOptions.java:17`:* "`UpdateActivityExecutionOptionsRequest` and `UpdateActivityOptionsResponse` use the same type to store options, which means the available fields will always match. Instead of adding yet another class, we can return `UpdateActivityOptions`."

Deleted `ActivityExecutionOptions.java`; `updateOptions` returns `UpdateActivityOptions`.

**Comment H** — *`UpdateActivityOptions.java:19`:* "Consider alternative design: `UpdateActivityOptions` does not have `restoreOriginal` field. Instead, `ActivityHandle` has an additional method `restoreOriginalOptions`."

Removed the field, setter, getter, and both `Preconditions` checks; added `restoreOriginalOptions()` to `UntypedActivityHandle` and both impls. Dropped the two tests that asserted the now-gone validation.

**Comment I** — *`ActivityClientCallsInterceptor.java:424` and `:464`* (two suggestions): `private final UnpauseActivityOptions options;` / `private final ResetActivityOptions options;`

Both inputs now carry the options object instead of exploded fields; the invoker reads `input.getOptions()`.

**Comment J** — *`ActivityClientCallsInterceptor.java:556`:* "`UpdateActivityOptionsOutput` should have the final options object, not Proto object. The conversion should happen inside the root interceptor."

Output holds `UpdateActivityOptions`; conversion moved from `ActivityHandleImpl.fromProto` into `RootActivityClientInvoker.toUpdateActivityOptions`.

**Comment K** — *`RootActivityClientInvoker.java:349`:* "We should remove payload fields that were not requested (to support older/buggy servers)."

New `stripUnrequestedPayloads` clears `input`/`outcome` on the response and `heartbeat_details`/`last_failure` on `info` when the corresponding flag was false.

**Comment L** — *`UntypedActivityHandle.java:135`:* "I think `DescribeActivityOptions.java` file is missing."

No code change — the file was committed locally but the branch had unpushed commits. Resolved by pushing.

**Comment M** — *`ActivityHandleOperatorCommandsTest.java:49`:* "lol" — no action.

---

## Ruby counterparts

| Ruby change | Inspired by |
|---|---|
| `update_options` loses the `restore_original:` kwarg and both `ArgumentError` guards; new `ActivityHandle#restore_original_options(rpc_options:)` | **Comment H** (restoreOriginal → own method) |
| `implementation.rb#update_activity_options` returns `ActivityExecutionOptions._from_proto(resp.activity_options)` instead of the raw proto; `activity_handle.rb` no longer converts | **Comment J** (output holds final options, convert in root interceptor) |
| `describe_activity` clears `resp.input`, `resp.outcome`, `resp.info.heartbeat_details`, `resp.info.last_failure` when not requested | **Comment K** (strip un-requested payloads) |
| `Description#failure` → `#outcome_failure` | **Comment F** (rename to differentiate from `last_failure`) |
| RBS/RBI updated for both renames and the new method; tests updated, two obsolete validation tests deleted | consequences of **F** and **H** |

Four Java comments have no Ruby counterpart, deliberately:

- **Comment C (EncodedValues)** — Ruby has no `Values` type, and `input(hints:)` / `heartbeat_details(hints:)` already return the whole array, which is the shape the Java change moves toward.
- **Comment E (null-coalescing)** — Ruby's protobuf returns `nil` for unset message fields rather than a default instance, so `@raw_description.outcome&.value` is required, not redundant.
- **Comment B (serialization context)** — Ruby's `DataConverter` has no context mechanism; nothing to attach.
- **Comments A, D, G, I** — no Ruby analogue: Ruby's `Description` stores only `@raw_description`, hints are a single value, `ActivityExecutionOptions` isn't redundant there (Ruby's update input is kwargs, not a class), and Ruby's interceptor inputs have no options objects to hold.

---

## Verification

Java: 40 tests (17 operator-commands, 14 description unit, 8 interceptor, 1 handle-build), 0 failures, spotless clean.
Ruby: 17 + 1 + 1 runs across the three operator-command files, 411 assertions, 0 failures; steep clean, RuboCop clean across 191 files.

## Notes

- Removing `restoreOriginal` also removed the "at least one option must be set" guard, since `UpdateActivityOptions` is now both an input and a return type and that check would break the return direction. An empty update now reaches the server rather than failing locally.
- `UpdateActivityOptions` as a return type carries builder-validation semantics it does not need; it works, but a reviewer may notice the dual role.
