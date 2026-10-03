# Temporal release automation

The release is authorized by merging one new `releases/vX.Y.Z` file into a branch watched by `temporal-release-candidate.yml`: `main` or a branch matching `release-*`

```mermaid
flowchart TD
    merge[Release-note file merged] --> start[Actions: validate candidate]
    start --> workflow[Temporal: start ReleaseWorkflow]
    start --> native[Actions matrix: build native binaries]
    start --> maven[Actions: build and sign Maven payload]
    native --> nativeArtifacts[Actions: upload native artifacts]
    maven --> mavenArtifact[Actions: upload Maven artifact]
    nativeArtifacts --> workers[Actions: start release-specific Workers]
    mavenArtifact --> workers
    workers --> ready[Temporal update: buildsReady]
    ready --> discover[Activity: freeze artifact IDs and digests]
    discover --> publish[Activity: publishRelease]
    publish --> sonatype[Reconcile Sonatype staging and Portal]
    sonatype --> central[Require exact Maven release]
    central --> draft[Create or reconcile GitHub draft]
    draft --> assets[Attach native archives and SHA256SUMS]
    assets --> public[Publish GitHub release]
```

## Components

- `temporal-release-candidate.yml`: merge trigger, native build matrix, signed Maven build, Actions artifact storage, and short-lived Workers.
- `build.py`: Called by `temporal-release-candidate.yml`. Creates reproducible native archives and the signed, manifested Maven payload.
- `release.py` contains the Workflow, Activities, external-state reconciliation, and the `start` and `publish` command entry points.
