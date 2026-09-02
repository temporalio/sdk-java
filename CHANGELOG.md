<!--
High-level release notes.
Loosely based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

When your PR includes a user-facing change, add an entry below under the
appropriate heading (create the heading if it does not yet exist). Within
each heading content can be free-form. Feel free to include examples, links
to docs, or any other relevant information.

### Added            — new features
### Changed          — changes in existing functionality
### Deprecated       — soon-to-be-removed features
### :boom: Breaking Changes — removed or backwards-incompatible features
### Fixed            — notable bug fixes
### Security         — notable security fixes
-->

# Changelog

## [Unreleased]

### Added

#### Standalone Activity operator commands

- `UntypedActivityHandle` and `ActivityHandle` now support operator commands for standalone
   activities: `pause()`, `unpause()`, `reset()`, `updateOptions()` and `restoreOriginalOptions()`.
  `updateOptions()` takes `ActivityOptionsUpdate` values built from the keys on
  `ActivityOptionsKeys`, via `ActivityOptionsKey.valueSet()` to set an option or
  `ActivityOptionsKey.valueUnset()` to clear it, and returns the server's resolved
  `ActivityExecutionOptions`.
- Added opt-in payload flags to `DescribeActivityOptions`: `setIncludeInput()`,
  `setIncludeOutcome()`, `setIncludeHeartbeatDetails()` and `setIncludeLastFailure()`, all
  defaulting to `false`.
- Added missing `ActivityExecutionDescription` fields: `getExecutionTime()`, `getStartDelay()`
  and `getTotalHeartbeatCount()`.

### :boom: Breaking Changes

- `ActivityExecutionDescription` payload fields are now opt-in and must be requested via
  `DescribeActivityOptions`: `getInput()`, `getResult()`, `getHeartbeatDetails()` and
  `getLastFailure()`. Each has a matching `hasInput()` / `hasResult()` /
  `hasHeartbeatDetails()` / `hasLastFailure()` predicate.

### Changed

### Deprecated

### Fixed

### Security
