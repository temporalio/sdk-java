# Signal with start inside workflows

The generated workflow API atomically signals an external workflow, starting it
when necessary. It requires a server supporting the system workflow service.

```java
ExternalWorkflowStub external = Workflow.signalWithStartWorkflow(
    "TargetWorkflow", "target", "target-queue", "notify",
    SignalWithStartWorkflowOptions.newBuilder()
        .setArgs(input)
        .setSignalArgs(signal)
        .build());
```

Import `Workflow`, `SignalWithStartWorkflowOptions`, and `ExternalWorkflowStub`
from `io.temporal.workflow`. The generated options builder contains the API's
options, including timeouts, retry policy, memo, conflict policy, and flattened
static summary/details setters. Workflow type, ID, task queue, and signal name
are required parameters. An overload without options uses defaults. The request
is constructed internally. Namespace defaults to the caller's namespace. Request ID is omitted:
the system service manages it.

The call returns after signal delivery/start completes. The returned external
stub supports further signals and cancellation. Use
`Async.function(Workflow::signalWithStartWorkflow, workflowType, workflowId,
taskQueue, signalName, options)` for a promise.

The typed overload checks workflow and signal arguments against their interface:

```java
ExternalWorkflowStub external = Workflow.signalWithStartWorkflow(
    target::run, input, "target", "target-queue",
    target::notifySignal, signal,
    SignalWithStartWorkflowOptions.newBuilder().setRunTimeout(timeout).build());
```

Create `target` with `Workflow.newExternalWorkflowStub(TargetWorkflow.class, "target")`.
Pass bound workflow and signal methods followed directly by their arguments.
Typed overloads cover zero through six workflow arguments and zero or one signal
argument, including void workflow methods. Other arities use the name-based API.
The compiler checks arguments and retains generic workflow result information in
the functional interface. The operation itself returns an external stub, not the
workflow result.

The existing external invocation handler probes method names, arguments, result
types, target execution, and annotation-derived retry/cron options using scoped
thread locals. It sends no commands while probing and restores normal invocation
behavior afterward, including on failure. Query/update methods and receiver
lambdas are rejected. Typed calls reject `setArgs` or `setSignalArgs` in options.
Both forms support `Async.function` and an overload omitting options.

Workflow ID and task queue remain explicit. The IDL does not define how probed
options map to request fields, precedence between those options and explicit
options, or relationships between the workflow and signal references. Automatic
options merging and cross-reference constraints are deferred rather than
hardcoded in the generator. Explicit ID, task queue, and options govern this call.

`Workflow` inherits generated operations from `WorkflowOperations`. New WIT
operations become available after regeneration, without adding handwritten
options, capture proxies, stubs, or facade methods. The output transform, request
fields, and serialization-context hook are declared in WIT. The converter uses
the destination namespace and workflow ID for nested payloads, codecs, and
failures; the outer system envelope remains server-readable.

This operation serializes the request and deserializes a response with no user
payloads. Future operations returning embedded payloads must use generic response
models and propagate concrete result types from the API to transfer conversion.
