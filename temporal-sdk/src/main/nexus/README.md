# System Nexus bindings

The WIT definitions here are a temporary copy of the shared multi-language
definitions in nex-gen's `advanced/samples/inputs/workflow-service.wit` and
`deps/nexus-temporal-types/model.wit`. Keep the copies identical; Java directives
belong in the shared source alongside the other languages. The SDK will
eventually consume these definitions from the API repo instead. Protobuf
descriptors from
`temporal-serviceclient:generateProto` validate the mappings during generation.

Regenerate with nexgen 0.2.12 from temporalio/nexgen#245, built with its
`advanced` feature, and `google-java-format` 1.24.0 on `PATH`:

```sh
./gradlew :temporal-sdk:generateNexusSystemApi \
  -PnexgenBinary=/path/to/nexgen
./gradlew --offline spotlessApply
```

Generated Java and `system-nexus-roots.txt` are checked in under
`src/main/generated/io/temporal/workflow`, an isolated main Java source root.
The generator uses `--targets workflow`, `--package-name io.temporal.workflow`,
and the WIT annotation `@nexus.operations-class java="WorkflowOperations"`.
`Workflow` extends this
generated base once; additional operations require no handwritten facade methods.
Generated operations take only function references/names and their inputs as
parameters. All other fields live in a mandatory generated options object.
Its builder validates required caller values; defaults and merged fields resolve
at invocation. Typed calls derive an omitted workflow ID from the workflow
reference; name-based calls require an explicit ID before dispatch.
All generated API types are available from the workflow package.
Typed overloads take bound methods followed directly by arguments. SDK-owned
call-extractor hooks resolve annotated workflow/signal names and arguments
without executing commands; the generated wrapper constructs the request.
Primary callable arities zero through six and other callable arities zero or one
come from Java rendering policy and the IDL's primary marker. No operation names
or composite-operation roles are encoded in the generator. Java-only
`merge-from-function` hooks declare ID resolution plus retry and cron merging from the workflow
method's original annotations. The generator retains raw probe metadata, and
SDK helpers apply normal client precedence without synthesizing `WorkflowOptions`.
Explicit workflow IDs must match the workflow reference, which must not be
pinned to a run ID. Signal execution bindings are ignored.
The manifest derives from operation input/output protobuf transfer types. The service client's Java
payload visitor generator consumes it, including payload-free response roots,
without depending on compiled SDK classes.

Regeneration passes `--format` to nexgen, which invokes the installed
`google-java-format` command. Version 1.24.0 matches the SDK's Spotless
configuration. Nexgen does not download the formatter. Normal SDK builds use
the checked-in output and do not require either tool.

`TemporalModelConverters` lives in `io.temporal.internal.nexus.system` and
contains SDK-owned conversion and output-transform helpers. Generated dispatch
and service helpers live in `io.temporal.workflow.internal`. The nexgen
sample support file contains its own copies of these implementations. The emitted
`NexgenRuntime` contains only payload-sequence decoding. Converter scoping lives
in `SystemNexusConverterContext`; generated transfer converters obtain the active
converter through `TemporalModelConverters.currentDataConverter()` and fail
when no scope is active, matching Python. Protobuf field access is generated
directly. Nex-gen assembles the required runtime helpers from Rust strings.
Normal Java SDK builds do not require Rust or nex-gen.

The SDK wraps any configured `DataConverter` in `SystemNexusDataConverter` for
system operations. That wrapper handles the outer envelope and delegates nested
user conversion; custom converters do not need to recognize system envelopes.
The shared payload visitor descends into marked envelopes for payload traversal.
`CodecDataConverter` contains no system Nexus handling.
