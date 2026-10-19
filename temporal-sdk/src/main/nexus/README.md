# System Nexus bindings

The WIT definitions here describe the workflow-facing Java models for the
vendored workflow service. They retain the upstream wire schema and add Java
native type and serialization context annotations. Protobuf descriptors from
`temporal-serviceclient:generateProto` validate the mappings during generation.

Regenerate with nex-gen 0.2.9 built with its `advanced` feature:

```sh
./gradlew :temporal-sdk:generateNexusSystemApi \
  -PnexgenBinary=/path/to/nexgen
./gradlew --offline spotlessApply
```

Generated Java and `system-nexus-roots.txt` are checked in. The manifest derives
from operation input/output protobuf transfer types. The service client's Java
payload visitor generator consumes it, including payload-free response roots,
without depending on compiled SDK classes.

`TemporalModelConverters` contains the SDK-owned conversion helpers. The emitted
`NexgenRuntime` contains common generated Java code and is rendered from a Rust
string in nex-gen. Normal Java SDK builds do not require Rust or nex-gen.
