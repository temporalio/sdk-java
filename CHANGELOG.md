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

### Changed
- Release notes for all future releases are now in a single CHANGELOG.md file. `releases` directory with old release
  notes is kept for historical reference.

### Fixed
- Test server now honors retry expiration deadlines that fall exactly on a whole second. Previously such deadlines were
  ignored and retries were scheduled past them instead of failing with `RETRY_STATE_TIMEOUT`.
- Spring Boot: closing the application context now waits for workers to finish in-flight tasks, bounded by
  `spring.lifecycle.timeout-per-shutdown-phase`. Previously the root namespace workers were shut down without waiting,
  so their pollers and task completions hit the already closed service stubs (`UNAVAILABLE: Channel shutdown invoked`),
  and the non-root namespace workers were not shut down when their own context closed.

## Previous releases

Changelogs for releases 1.40 and older are available in [releases](/releases) directory.
