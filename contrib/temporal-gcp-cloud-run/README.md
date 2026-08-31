# Temporal Google Cloud Run support

This module configures a Temporal worker for Google Cloud Run from instance metadata, for both Cloud Run **worker pools** and Cloud Run **services**. It derives the worker's Temporal identity and its `WorkerDeploymentVersion` from Cloud Run instance metadata, so every Cloud Run revision registers as a distinct, `PINNED` Worker Deployment Version.

The primary API is `WorkerIdPlugin`. Register it once on your workflow client and it propagates to every worker created from that client, setting the client identity and the worker deployment version automatically. This mirrors the `CloudRunOpenTelemetryPlugin` in this same module.

> Experimental: Google Cloud Run support is experimental and may change without notice.

## Quick start

Add `temporal-gcp-cloud-run` next to your Temporal SDK dependency, then register the plugin on the workflow client options:

```java
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.gcp.cloudrun.WorkerIdPlugin;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;

public final class Main {
  public static void main(String[] args) {
    WorkflowServiceStubs service =
        WorkflowServiceStubs.newServiceStubs(
            WorkflowServiceStubsOptions.newBuilder()
                .setTarget("my-namespace.tmprl.cloud:7233")
                .build());

    // Registering the plugin on the client:
    //  - reads Cloud Run instance metadata once while the client is configured, and
    //  - sets the client identity to the derived worker identity (unless you set one yourself).
    WorkflowClient client =
        WorkflowClient.newInstance(
            service,
            WorkflowClientOptions.newBuilder()
                .setNamespace("my-namespace")
                .setPlugins(new WorkerIdPlugin())
                .build());

    WorkerFactory factory = WorkerFactory.newInstance(client);

    // The plugin propagates from the client to workers and sets each worker's deployment version
    // (with worker versioning enabled and a PINNED default behavior). No per-worker wiring needed.
    Worker worker = factory.newWorker("orders");
    worker.registerWorkflowImplementationTypes(OrderWorkflowImpl.class);
    worker.registerActivitiesImplementations(new OrderActivitiesImpl());

    factory.start();
  }
}
```

You can also register the plugin on `WorkflowServiceStubsOptions.Builder.setPlugins(...)`; from there it propagates to the client and workers as well.

## How it works

`WorkerIdPlugin` reads Cloud Run instance metadata through `GoogleCloudRunMetadata`, which resolves three values:

- **name** (the Temporal deployment name): the first non-empty of `CLOUD_RUN_WORKER_POOL` (set on Cloud Run worker pools) then `K_SERVICE` (set on Cloud Run services).
- **revision**: the first non-empty of `CLOUD_RUN_REVISION` (worker pools) then `K_REVISION` (services).
- **instanceId**: read from the Cloud Run metadata server with a single HTTP `GET` to `http://metadata.google.internal/computeMetadata/v1/instance/id` with the required `Metadata-Flavor: Google` header. The metadata server is available on both worker pools and services.

Worker pools receive `CLOUD_RUN_WORKER_POOL` and `CLOUD_RUN_REVISION` and no `K_*` variables, while services receive `K_SERVICE` and `K_REVISION`, so resolving each value from the worker-pool variable first and the service variable second supports both.

The plugin then applies the metadata through the SDK's plugin hooks:

- **Client** (`configureWorkflowClient`): sets the client identity to `<instanceId>@<revision>` (falling back to `<instanceId>@<name>` and then the bare `<instanceId>`), but only when you have not already set an identity, so a user-provided identity always wins. The metadata is fetched here, once, and cached.
- **Worker** (`configureWorker`): sets the worker deployment version — the name becomes the deployment name and the revision becomes the build id — with worker versioning enabled and `VersioningBehavior.PINNED` as the default, so in-flight workflows stay on the Cloud Run revision that started them (a per-workflow `@WorkflowVersioningBehavior` takes precedence).

Because the metadata server is only reachable from a Cloud Run instance, the plugin **fails fast**: the fetch in `configureWorkflowClient` throws `IllegalStateException` when the metadata server cannot be reached (which usually means the process is not running on Google Cloud Run), and `configureWorker` throws `IllegalStateException` when the name or revision is not set (which usually means the process is not running on a Cloud Run worker pool or service). The plugin does not silently no-op off-platform.

## Reading the metadata directly

If you prefer to read the values yourself, or to fetch the metadata once and pass it in, use `GoogleCloudRunMetadata` directly:

```java
GoogleCloudRunMetadata metadata = GoogleCloudRunMetadata.fetch();
String identity = metadata.workerIdentity();
WorkerDeploymentVersion version = metadata.workerDeploymentVersion();

// Or hand the already-fetched metadata to the plugin to skip its own fetch:
WorkerIdPlugin plugin = new WorkerIdPlugin(metadata);
```

`GoogleCloudRunMetadata.fetch(String metadataUrl, Duration timeout)` overrides the metadata URL or the request timeout.

This module depends only on the Temporal SDK at compile time and uses the JDK's `HttpURLConnection` for the metadata request, so it adds no additional runtime dependencies.
