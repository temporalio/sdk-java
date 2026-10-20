# Signal with start inside workflows

The generated workflow API atomically signals an external workflow, starting it
when necessary. It requires a server supporting the system workflow service.

```java
ExternalWorkflowStub external = Workflow.signalWithStartWorkflow(
    SignalWithStartWorkflowRequest.newBuilder()
        .setWorkflow("TargetWorkflow")
        .setSignal("notify")
        .setId("target")
        .setTaskQueue("target-queue")
        .setArgs(Collections.singletonList(input))
        .setSignalArgs(Collections.singletonList(signal))
        .build());
```

Import `Workflow`, `SignalWithStartWorkflowRequest`, and `ExternalWorkflowStub`
from `io.temporal.workflow`. The generated request builder contains the API's
options, including timeouts, retry policy, memo, conflict policy, and flattened
static summary/details setters. Workflow ID and task queue are explicit request
fields. Namespace defaults to the caller's namespace. Request ID is omitted:
the system service manages it.

The call returns after signal delivery/start completes. The returned external
stub supports further signals and cancellation. Use
`Async.function(Workflow::signalWithStartWorkflow, request)` for a promise.

`Workflow` inherits generated operations from `WorkflowOperations`. New WIT
operations become available after regeneration, without adding handwritten
options, capture proxies, stubs, or facade methods. The output transform, request
fields, and serialization-context hook are declared in WIT. The converter uses
the destination namespace and workflow ID for nested payloads, codecs, and
failures; the outer system envelope remains server-readable.
