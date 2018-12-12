# Changelog

## v2.2.0
- Added support for workflow and activity server side retries.
- Clean worker shutdown. Replaced Worker shutdown(Duration) with Worker shutdown, shutdownNow and awaitTermination.
- Fixed thread exhaustion with a large number of parallel async activities.

## v2.1.3
- Added RPC headers needed to enable sticky queries. Before this change
queries did not used cached workflows.

## v2.1.2
- Requires minimum server release v0.4.0
- Introduced WorkerFactory and FactoryOptions
- Added sticky workflow execution, which is caching of a workflow object between decisions. It is enabled by default, 
to disable use FactoryOptions.disableStickyExecution property.
- Updated Thrift to expose new types of service exceptions: ServiceBusyError, DomainNotActiveError, LimitExceededError
- Added metric for corrupted signal as well as metrics related to caching and evictions.

## v1.0.0 (06-04-2018)
- POJO workflow, child workflow, activity execution.
- Sync and Async workflow execution.
- Query and Signal workflow execution.
- Test framework.
- Metrics and Logging support in client.
- Side effects, mutable side effects, random uuid and workflow getVersion support.
- Activity heartbeat throttling.
- Deterministic retry of failed operation.


