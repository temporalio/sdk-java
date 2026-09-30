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
- Experimental `NexusClientCallsInterceptor.GetNexusOperationResultInput` now takes a single nullable
  `NexusSerializationContext` in place of separate endpoint, service and operation arguments, and
  exposes it through `getSerializationContext()` in place of `getEndpoint()`, `getService()` and
  `getOperation()`.

### Fixed
- A standalone Nexus operation handle returned when an ID conflict policy of use-existing reuses a
  running operation now uses that operation's endpoint, service and operation for its serialization
  context. Previously it used the ones named by the start request, which may differ.

### Changed
- Release notes for all future releases are now in a single CHANGELOG.md file. `releases` directory with old release
  notes is kept for historical reference.

## Previous releases

Changelogs for releases 1.40 and older are available in [releases](/releases) directory.
