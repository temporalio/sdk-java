<!--
High-level release notes.
Loosely based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

When your PR includes a user-facing change, add an entry below under the
appropriate heading (create the heading if it does not yet exist). Within
each heading content can be free-form. Feel free to include examples, links
to docs, or any other relevant information.

### :boom: Breaking Changes — removed or backwards-incompatible features
### Added                   — new features
### Changed                 — changes in existing functionality
### Deprecated              — soon-to-be-removed features
### Fixed                   — notable bug fixes
### Security                — notable security fixes
-->

# Changelog

## [Unreleased]

### :boom: Breaking Changes
- `WorkflowOutboundCallsInterceptor` has new `sleep(Duration, TimerOptions)` and
  `await(Duration, TimerOptions, String, Supplier)` methods. Implementations that don't extend
  `WorkflowOutboundCallsInterceptorBase` need to add them, and Base subclasses override them to see calls with options.

### Added
- Wrap a codec-capable data converter in `HeaderEncodingDataConverter` to opt into automatic
  codec-only processing of payload headers, including tracing and context propagation, on workflow,
  activity, and schedule clients. Workers inherit their workflow client's converter. Ordinary
  `CodecDataConverter` retains legacy header behavior. Supply serialized but not already codec-encoded
  headers, and ensure decoders support legacy plaintext before enabling this for existing histories
  or schedules. Custom outer converter wrappers must forward the header capability.
- Applications can use the experimental `@TransferTypeConvertible` annotation and
  `TransferTypeConverter` interface to convert top-level model values to transfer types. The SDK
  applies this conversion to workflow, activity, Nexus, and schedule payloads, including failure
  details. `WorkflowClient.getOptions().getDataConverter()` returns the transfer-aware converter
  that the client uses.
- Worker heartbeats now report detected Graal native-image and Kotlin runtimes alongside the JVM
  version. Detection is best effort and respects the worker environment information opt-out.
  Factory registration overloads accept an implementation class for runtime detection, including
  Spring Boot workflow registrations.
- Added experimental `ChildWorkflowOptions.Builder.setVersioningOverride` and
  `VersioningOverride.OneTimeVersioningOverride` for explicit pinned, auto-upgrade, and one-time
  child workflow routing. Invalid child overrides are reported as `InvalidVersioningOverrideFailure`
  under `ChildWorkflowFailure`.
  Child workflow overrides and one-time routing require Temporal Server 1.32.0 or later.
- `WorkerFactoryOptions.Builder.setLoggerTagPrefix` that can be used to customized structured logging tags (MDC keys)
  set by Temporal SDK in worker context.
- Spring Boot: the `workers` attribute of `@WorkflowImpl`, `@ActivityImpl` and `@NexusServiceImpl` now resolves property
  placeholders such as `${my.worker.name}`, as `taskQueues` already does.
- `Workflow.sleep(Duration, TimerOptions)` and `Workflow.await(Duration, TimerOptions, Supplier)` let workflows set a
  summary on the timer behind a sleep or a timed await, like `Workflow.newTimer(Duration, TimerOptions)` already does.
- Add nonblocking `Async.await` overloads whose promises complete when a workflow condition becomes true or a timeout expires.
- Workflow tasks taking longer than 5 seconds now log a `[TMPRL1104]` warning reporting the task duration and the
  external storage downloads and uploads that contributed to it. The threshold is configurable with the
  `TEMPORAL_WORKFLOW_TASK_DURATION_WARN_SECONDS` environment variable.

### Changed
- Moved the Spring AI integration to [temporalio/ai-integrations](https://github.com/temporalio/ai-integrations/tree/main/java/spring-ai).
  `io.temporal:temporal-spring-ai` now publishes a one-time relocation POM to `io.temporal:spring-ai:0.1.0`;
  the integration is versioned independently of the SDK. The standalone release requires Java 17+, Spring AI 2,
  Spring Boot 4, and Temporal Java SDK 1.40.0+. Spring AI 1 / Spring Boot 3 applications should remain on
  `io.temporal:temporal-spring-ai:1.40.x` until they migrate. Existing Spring AI 1 workflow histories are not
  replay-compatible; complete those executions or use separate worker deployments.
- Release notes for all future releases are now in a single CHANGELOG.md file. `releases` directory with old release
  notes is kept for historical reference.
- Added versioned scheduling for asynchronous Temporal stub calls. When enabled, switching between a stub method
  reference such as `Async.function(activities::first)` and an equivalent lambda such as
  `Async.function(() -> activities.first())` preserves command order during replay. The call runs on a workflow thread,
  so workflow outbound interceptors can wait while scheduling it. The flag remains disabled by default, and existing
  unflagged histories retain their previous behavior.
- `TemporalDevServer`, `TemporalDevServerOptions`, `TestWorkflowEnvironment.startLocal`, and the `useDevServer` methods
  of `TestWorkflowRule` and `TestWorkflowExtension` are no longer marked experimental. `TemporalDevServerOptions` remain
  specific to the Temporal CLI dev server implementation and may no longer be valid if that implementation changes.

### Fixed
- Schedule action headers are now preserved when describing and updating a schedule.
- `ActivityClient` now sends the `temporal-namespace` gRPC header like `WorkflowClient` and `ScheduleClient` do,
  so standalone activity calls are no longer rejected with `PERMISSION_DENIED` by namespace-scoped auth (e.g.
  Temporal Cloud API keys).
- Kotlin `Async` method references compiled with Kotlin 2.4 now retain their inline behavior,
  preventing `NonDeterministicException` when replaying workflows started with Kotlin 2.2. For
  workflows already started with Kotlin 2.4 and SDK 1.40 or earlier, set the
  `temporal.kotlin.disableStaticAdapterUnwrapping` JVM system property to preserve the previous
  behavior during replay.
- Test server now honors retry expiration deadlines that fall exactly on a whole second. Previously such deadlines were
  ignored and retries were scheduled past them instead of failing with `RETRY_STATE_TIMEOUT`.
- Worker heartbeats now list each plugin name once. Previously a plugin registered on both the
  `WorkflowClient` and the `WorkerFactory` was listed twice.

## Previous releases

Changelogs for releases 1.40 and older are available in [releases](/releases) directory.
