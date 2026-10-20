# Signal with start inside workflows

The generated workflow operation atomically signals an external workflow,
starting it when necessary. It requires a server supporting the system workflow
service. Its request model is a low-level serialized representation; an ergonomic
API with required parameters and options containing user values is future work.

Request construction must serialize user values with the configured converter
and destination workflow context. Given that converter as `configuredConverter`:

```java
String namespace = Workflow.getInfo().getNamespace();
String workflowId = "target";
DataConverter converter = configuredConverter.withContext(
    new WorkflowSerializationContext(namespace, workflowId));
ExternalWorkflowStub external = Workflow.signalWithStartWorkflow(
    SignalWithStartWorkflowRequest.newBuilder()
        .setWorkflow("TargetWorkflow")
        .setSignal("notify")
        .setId(workflowId)
        .setNamespace(namespace)
        .setTaskQueue("target-queue")
        .setArgs(converter.toPayloads(input).get())
        .setSignalArgs(converter.toPayloads(signal).get())
        .build());
```

Import `Workflow`, `SignalWithStartWorkflowRequest`, and `ExternalWorkflowStub`
from `io.temporal.workflow`, `DataConverter` from
`io.temporal.common.converter`, and `WorkflowSerializationContext` from
`io.temporal.payload.context`. Payload-bearing fields, including memo, headers,
static summary/details, and search attributes, retain serialized protobuf values.
The generated builder also carries timeouts, retry policy, and conflict policy.
Namespace defaults to the caller's namespace. Request ID is omitted: the system
service manages it.

The call returns after signal delivery/start completes. The returned external
stub supports further signals and cancellation. Use
`Async.function(Workflow::signalWithStartWorkflow, request)` for a promise.

`Workflow` inherits generated operations from `WorkflowOperations`. New WIT
operations become available after regeneration. The output transform, request
fields, and serialization-context hook are declared in WIT. Transfer conversion
preserves serialized payloads and whole batches without decoding them. Future
typed APIs can decode embedded results with their expected types. The outer
system envelope remains server-readable.
