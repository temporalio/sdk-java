# Signal with start inside workflows

The generated workflow API atomically signals an external workflow, starting it
when necessary. It requires a server supporting the system workflow service.

```java
ExternalWorkflowStub external = Workflow.signalWithStartWorkflow(
    "TargetWorkflow", new Object[] {input},
    "notify", new Object[] {signal},
    SignalWithStartWorkflowOptions.newBuilder()
        .setId("target")
        .setTaskQueue("target-queue")
        .setRunTimeout(timeout)
        .build());
```

Import `Workflow`, `SignalWithStartWorkflowOptions`, and `ExternalWorkflowStub`
from `io.temporal.workflow`. The generated options builder contains the API's
options, including timeouts, retry policy, memo, conflict policy, and flattened
static summary/details setters. Workflow ID and task queue are required options;
the builder rejects missing values. Only the workflow and signal references or
names and their inputs are direct parameters. Every overload requires a non-null
options object. The request
is constructed internally. Namespace defaults to the caller's namespace. Request ID is omitted:
the system service manages it.

The call returns after signal delivery/start completes. The returned external
stub supports further signals and cancellation. Use
`Async.function(() -> Workflow.signalWithStartWorkflow(workflowType, args,
signalName, signalArgs, options))` for a promise.

The typed overload checks workflow and signal arguments against their interface:

```java
ExternalWorkflowStub external = Workflow.signalWithStartWorkflow(
    target::run, input,
    target::notifySignal, signal,
    SignalWithStartWorkflowOptions.newBuilder()
        .setId("target").setTaskQueue("target-queue").setRunTimeout(timeout).build());
```

Create `target` with `Workflow.newExternalWorkflowStub(TargetWorkflow.class, "target")`.
Pass bound workflow and signal methods followed directly by their arguments.
Typed overloads cover zero through six workflow arguments and zero or one signal
argument, including void workflow methods. Other arities use the name-based API.
The compiler checks arguments and retains generic workflow result information in
the functional interface. The operation itself returns an external stub, not the
workflow result.

The existing external invocation handler probes method names, arguments, result
types, target execution, and the original annotated method using scoped
thread locals. It sends no commands while probing and restores normal invocation
behavior afterward, including on failure. Query/update methods and receiver
lambdas are rejected. Both APIs take invocation arguments directly; options do
not contain argument fields.
Both forms support `Async.function`.

Typed calls merge the workflow method's `@MethodRetry` and `@CronSchedule` with
explicit retry/cron options, matching the client SDK. Retry fields use
`RetryOptions.merge(annotation, explicitOptions)`; explicit non-default settings
take precedence over annotation values. Cron uses the annotation when the
explicit value is null; an explicit empty string disables the annotated schedule.
The signal method's annotations are not used for these fields. Defaults apply
when retry/cron options are unset. Untyped calls have no method annotations to merge.

The shared WIT declares `@nexus.merge-from-function function="workflow"` hooks
on the retry and cron fields. Java generation retains the function's raw probe
metadata and calls the selected SDK merge helpers, without synthesizing
`WorkflowOptions` or hardcoding retry/cron policy in the generator. This directive
currently affects Java only.

Workflow ID and task queue remain explicit options. Relationships between workflow and
signal references are not declared and cross-reference constraints remain
deferred. Other options use their explicit values and existing defaults.

`Workflow` inherits generated operations from `WorkflowOperations`. New WIT
operations become available after regeneration, without adding handwritten
options, capture proxies, stubs, or facade methods. The output transform, request
fields, and serialization-context hook are declared in WIT. The converter uses
the destination namespace and workflow ID for nested payloads, codecs, and
failures; the outer system envelope remains server-readable.

`SystemSignalWithStartIntegrationTest` verifies this converter selection against a
supporting server with a signing codec, overlapping calls to distinct destination
workflows, payload inspection in history, and replay. Converter unit tests cover
valid and unknown versioning override shapes; unknown shapes are rejected.

This operation serializes the request and deserializes a response with no user
payloads. Future operations returning embedded payloads must use generic response
models and propagate concrete result types from the API to transfer conversion.
