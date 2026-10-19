# Signal with start inside workflows

Signal with start atomically signals an external workflow, starting it when
necessary. It uses the Temporal system Nexus endpoint and requires a server
that supports that service.

```java
TargetWorkflow target = Workflow.newSignalWithStartWorkflowStub(
    TargetWorkflow.class,
    SignalWithStartWorkflowOptions.newBuilder()
        .setWorkflowId("target")
        .setTaskQueue("target-queue")
        .build());
TargetWorkflow external = Workflow.signalWithStart(
    target,
    () -> target.run(input),
    () -> target.notifySignal(signal));
```

The two lambdas capture exactly one workflow call and one signal call. Both
must use `target`; capture does not start the workflow. The operation waits for
signal delivery/start to complete and returns an external workflow stub for
signaling and cancellation. It does not wait for the target workflow's result.
Use `Async.function(() -> Workflow.signalWithStart(...))` for a `Promise`.

An untyped equivalent is available through
`Workflow.newUntypedSignalWithStartWorkflowStub`. Options default the task queue
to the caller's queue and generate a deterministic workflow ID when omitted.
The destination namespace is the caller's namespace. Serialization uses that
namespace and the destination workflow ID for nested payloads, codecs, and
failure conversion; the system protobuf envelope remains server-readable.

Generated outbound interceptor hooks expose the system operation independently
of ordinary Nexus operations. Existing outbound interceptor implementations
remain compatible through default methods and the forwarding base class.
