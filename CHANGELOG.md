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

### Added
- Added experimental `ChildWorkflowOptions.Builder.setVersioningOverride` and
  `VersioningOverride.OneTimeVersioningOverride` for explicit pinned, auto-upgrade, and one-time
  child workflow routing. Invalid child overrides are reported as `InvalidVersioningOverrideFailure`
  under `ChildWorkflowFailure`.
  Child workflow overrides and one-time routing require Temporal Server 1.32.0 or later.
- `WorkerFactoryOptions.Builder.setLoggerTagPrefix` that can be used to customized structured logging tags (MDC keys)
  set by Temporal SDK in worker context.

### Changed
- Moved the Spring AI integration to [temporalio/ai-integrations](https://github.com/temporalio/ai-integrations/tree/main/java/spring-ai).
  `io.temporal:temporal-spring-ai` now publishes a one-time relocation POM to `io.temporal:spring-ai:0.1.0`;
  the integration is versioned independently of the SDK.
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
- Test server now honors retry expiration deadlines that fall exactly on a whole second. Previously such deadlines were
  ignored and retries were scheduled past them instead of failing with `RETRY_STATE_TIMEOUT`.

## Previous releases

Changelogs for releases 1.40 and older are available in [releases](/releases) directory.
