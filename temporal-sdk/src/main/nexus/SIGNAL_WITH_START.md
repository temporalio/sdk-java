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

`Workflow` inherits generated operations from `WorkflowOperations`. New WIT
operations become available after regeneration, without adding handwritten
options, capture proxies, stubs, or facade methods. The output transform, request
fields, and serialization-context hook are declared in WIT. The converter uses
the destination namespace and workflow ID for nested payloads, codecs, and
failures; the outer system envelope remains server-readable.

This operation serializes the request and deserializes a response with no user
payloads. Future operations returning embedded payloads must use generic response
models and propagate concrete result types from the API to transfer conversion.
