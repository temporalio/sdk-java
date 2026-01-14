# Plugin System for Java Temporal SDK

## Overview

This PR implements a plugin system for the Java Temporal SDK, modeled after the Python SDK's plugin architecture but adapted to Java idioms and the existing SDK design patterns.

The plugin system provides a higher-level abstraction over the existing interceptor infrastructure, enabling users to:
- Modify configuration during client/worker creation
- Wrap execution lifecycles with setup/teardown logic
- Auto-propagate plugins from client to worker
- Bundle multiple customizations (interceptors, context propagators, etc.) into reusable units

## Design Decisions

### 1. No Base `Plugin` Interface

**Decision:** `ClientPlugin` and `WorkerPlugin` each define their own `getName()` method independently, rather than sharing a base `Plugin` interface.

**Rationale:** This matches the Python SDK's design. Python has separate `ClientPlugin` and `WorkerPlugin` with `name()` on each. I initially had a base `Plugin` interface but removed it to simplify.

**Alternative considered:** I could add a shared `Plugin` interface with just `getName()`. This would allow `List<Plugin>` instead of `List<?>` for storage. However, this adds an interface that serves no purpose other than type convenience, and Python doesn't have it.

### 2. `ClientPluginCallback` Interface (Module Boundary)

**Decision:** A `ClientPluginCallback` interface exists in `temporal-serviceclient`, which `ClientPlugin` (in `temporal-sdk`) extends.

**Rationale:** This is required due to Java's module architecture:
- `temporal-serviceclient` contains `WorkflowServiceStubs`
- `temporal-sdk` depends on `temporal-serviceclient` (not vice versa)
- `WorkflowServiceStubs.newServiceStubs(options, plugins)` needs to call plugin methods
- Since serviceclient cannot import from sdk, I define a minimal callback interface in serviceclient

This is the one structural difference from Python, which uses a single-package architecture where everything can import everything else.

### 3. `PluginBase` Convenience Class

**Decision:** I provide an abstract `PluginBase` class that implements both `ClientPlugin` and `WorkerPlugin`.

**Rationale:** This is a common Java pattern (like `AbstractList` for `List`).


### 4. `SimplePluginBuilder` with Private `SimplePlugin`

**Decision:** I provide a builder for creating plugins declaratively, with the implementation class kept private.

**Rationale:**
- Builder pattern is more natural in Java than Python's constructor with many parameters
- Private `SimplePlugin` is an implementation detail - users interact with the builder
- Allows changing implementation without breaking API

```java
PluginBase myPlugin = SimplePluginBuilder.newBuilder("my-plugin")
    .addWorkerInterceptors(new TracingInterceptor())
    .customizeClient(b -> b.setIdentity("custom"))
    .build();
```

### 5. No ServiceLoader Discovery

**Decision:** I do not include ServiceLoader-based plugin discovery.

**Rationale:**
- Python doesn't have this - just uses explicit `plugins=[]`
- ServiceLoader requires classes with no-arg constructors, which doesn't integrate with `SimplePluginBuilder`
- "Magic" discovery is harder to debug than explicit configuration
- Explicit plugin configuration is clearer and sufficient

We could consider adding it in though if there is interest.


## Files Changed

### New Files (`temporal-sdk/src/main/java/io/temporal/common/plugin/`)
- `ClientPlugin.java` - Client-side plugin interface
- `WorkerPlugin.java` - Worker-side plugin interface
- `PluginBase.java` - Convenience base class implementing both
- `SimplePluginBuilder.java` - Builder for declarative plugin creation

### Modified Files
- `WorkflowServiceStubs.java` - Added `newServiceStubs(options, plugins)` and `ClientPluginCallback` interface
- `WorkflowClientOptions.java` - Added `plugins` field with builder methods
- `WorkflowClientInternalImpl.java` - Applies `ClientPlugin.configureClient()` during creation
- `WorkerFactory.java` - Full plugin lifecycle (configuration, execution, shutdown)

### Test Files (`temporal-sdk/src/test/java/io/temporal/common/plugin/`)
- `PluginTest.java` - Core plugin interface tests
- `SimplePluginBuilderTest.java` - Builder API tests
- `WorkflowClientOptionsPluginTest.java` - Options integration tests

## Example Usage

### Custom Plugin
```java
public class TracingPlugin extends PluginBase {
    private final Tracer tracer;

    public TracingPlugin(Tracer tracer) {
        super("my-org.tracing");
        this.tracer = tracer;
    }

    @Override
    public WorkflowClientOptions.Builder configureClient(
            WorkflowClientOptions.Builder builder) {
        return builder.setInterceptors(new TracingClientInterceptor(tracer));
    }

    @Override
    public WorkerFactoryOptions.Builder configureWorkerFactory(
            WorkerFactoryOptions.Builder builder) {
        return builder.setWorkerInterceptors(new TracingWorkerInterceptor(tracer));
    }
}
```

### Using SimplePluginBuilder
```java
PluginBase metricsPlugin = SimplePluginBuilder.newBuilder("my-org.metrics")
    .customizeServiceStubs(b -> b.setMetricsScope(myScope))
    .addWorkerInterceptors(new MetricsInterceptor())
    .build();
```

### Client/Worker with Plugins
```java
WorkflowClientOptions clientOptions = WorkflowClientOptions.newBuilder()
    .setNamespace("default")
    .addPlugin(new TracingPlugin(tracer))
    .addPlugin(metricsPlugin)
    .build();

// Plugins that implement WorkerPlugin auto-propagate to workers
WorkerFactory factory = WorkerFactory.newInstance(client);
```

## Open Questions

1. **Mark as `@Experimental`?** - I've marked all public APIs as `@Experimental` to allow iteration. Is this appropriate?
