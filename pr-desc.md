# Add operator commands for standalone activities

Adds pause, unpause, reset, and update-options to standalone activities, plus
the describe surface needed to observe their effects.

Standalone activities already supported start, result, describe, cancel, and
terminate. This adds the four operator commands the server exposes for them, so
an operator can hold, resume, restart, and retune a running activity without
going through a workflow.

## API

On `ActivityHandle` / `UntypedActivityHandle`:

```java
void pause();
void pause(@Nullable String reason);
void unpause();
void unpause(UnpauseActivityOptions options);
void reset();
void reset(ResetActivityOptions options);
ActivityExecutionOptions updateOptions(UpdateActivityOptions options);
ActivityExecutionDescription describe(DescribeActivityOptions options);
```

New option types, all following the SDK's builder convention with
`newBuilder()`, `getDefaultInstance()`, `toBuilder()`, and value equality:

- `UnpauseActivityOptions` — reason, jitter.
- `ResetActivityOptions` — keep-paused, jitter, restore-original-options,
  reset-heartbeat.
- `UpdateActivityOptions` — task queue, the four timeouts, retry policy,
  priority, start delay, restore-original.
- `ActivityExecutionOptions` — the server's post-update view, returned by
  `updateOptions`.
- `DescribeActivityOptions` — the four payload opt-ins described below.

`updateOptions` derives a field mask from exactly the options set on the
builder, so unset fields are left untouched server-side.

## Describe: payload fields are opt-in

`DescribeActivityExecutionRequest` gates four payload-bearing fields behind
per-call flags (api#792). All four are now plumbed through
`DescribeActivityOptions` and **default to false**, matching Rust's
`ActivityDescribeOptions`:

```java
DescribeActivityOptions.newBuilder()
    .setIncludeInput(true)
    .setIncludeOutcome(true)
    .setIncludeHeartbeatDetails(true)
    .setIncludeLastFailure(true)
    .build();
```

This is a behavior change: `describe()` previously hard-coded
`includeHeartbeatDetails` and `includeLastFailure` to true. Callers that read
heartbeat details or the last failure must now ask for them. The rationale is
the one the proto gives — these fields carry arbitrarily large payloads and
shouldn't be fetched unless needed.

`ActivityExecutionDescription` gained accessors for the newly reachable data:

- `hasInput()`, `getInput(int, Class)`, `getInput(int, Class, Type)`,
  `getInput(Class)`, `getInput(Class, Type)`, `getInputCount()`
- `hasResult()`, `getResult(Class)`, `getResult(Class, Type)`, `getFailure()`
- `hasLastFailure()`, `getStartDelay()`, `getExecutionTime()`, `getRawResponse()`

The outcome is a result-or-failure oneof; it's flattened into `hasResult` /
`getResult` / `getFailure` rather than exposed as an outcome object, and none of
them throw — this follows `NexusOperationExecutionDescription`, which solves the
same problem for the same reason. `getFailure()` is the terminal outcome;
`getLastFailure()` remains the most recent attempt's failure and may be set
while the activity is still retrying.

Activity input is a payload *list*, unlike a Nexus operation's single payload,
which is why the input accessors are indexed while the Nexus ones aren't.

## Compatibility

The one behavior change is `describe()` no longer returning heartbeat details
and the last failure unless asked, described above.

`ActivityExecutionDescription`'s constructor signature changes from
`ActivityExecutionInfo` to `DescribeActivityExecutionResponse`. The `input` and
`outcome` fields live on the response, not on `info`, so the info alone can't
back the new accessors. This also brings the class in line with its siblings —
`WorkflowExecutionDescription` and `NexusOperationExecutionDescription` both
already take their full response.

In practice this constructor is internal: the only callers in the tree are
`RootActivityClientInvoker` and a unit test. It is public only because the
invoker lives in `io.temporal.internal.client` and Java has no internal
visibility. The one way a user could be affected is an interceptor that
synthesizes a `DescribeActivityOutput` rather than delegating to `next`.

Note that `ActivityExecutionMetadata.getScheduledTime()` is deliberately left
alone. It is the odd spelling out among the SDKs — the proto field is
`schedule_time`, and Ruby and Rust both expose `schedule_time` — but it has
shipped in every release since v1.35.0, and renaming it would be a compile break
bought for nothing this PR needs. Worth doing separately, most likely as a
deprecate-and-delegate rather than a rename.

## Interceptors

`ActivityClientCallsInterceptor` gains `pauseActivity`, `unpauseActivity`,
`resetActivity`, and `updateActivityOptions`, each with an `*Input`/`*Output`
pair, and `DescribeActivityInput` now carries the describe options.
`ActivityClientCallsInterceptorBase` picks up matching pass-through overrides
and a class-level `@Experimental` it was previously missing.

## Tests

- `StandaloneActivityOperatorCommandsTest` — functional coverage of each
  command against a real server, each asserting an observable server-side state
  change rather than just a successful RPC. Includes update-options on a paused
  activity, describe reporting `PAUSED` for an activity paused while scheduled,
  the heartbeat-preservation behavior of each command, and a test that describe's
  payload fields really are off by default.
- `ActivityExecutionDescriptionTest` — unit coverage of the new accessors,
  including both arms of the outcome oneof and multi-argument input.
- `ActivityHandleOperatorCommandsTest` — unit coverage that each command builds
  the right request.
- `ActivityClientCallsInterceptorBaseTest` — pass-through coverage.

Functional tests are gated on `SDKTestWorkflowRule.useExternalService`; the
embedded test server doesn't implement the standalone-activity APIs.

## Notes for reviewers

- `getInputs(Class<?>[], Type[])` returning `Object[]` is deliberate, and worth
  a look. It's the only user-facing `Object[]`-returning decode accessor in the
  SDK, and it coexists with the indexed `getInput(int, ...)`, so it is partly
  redundant. The SDK's existing abstraction for a typed argument list is
  `Values`/`EncodedValues` (what `DynamicActivity` receives); using it here was
  considered and not taken.
- `retry_state` on `ActivityExecutionOutcome` (api#843, server-populated since
  temporal#11321) is reachable via `getRawResponse()` but has no typed accessor
  yet.

## Upstream dependencies

Requires a server with the standalone-activity operator-command APIs enabled
(`frontend.activityAPIsEnabled`). Relevant API changes already merged and
reflected here: api#792 (describe opt-ins), api#834 (`PAUSED` status), api#844
(request IDs), api#846 (removed `reset_attempts`/`reset_heartbeat` from
Unpause), api#848 (`reset_heartbeat` back on Reset, paired with
temporal#11417), api#807 (`execution_time`), api#804 / temporal#10745
(`start_delay` on update-options).
