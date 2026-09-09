# Temporal Google Cloud Run worker identity support

This module derives a Temporal worker **identity** for Google Cloud Run from instance metadata, for both Cloud Run **worker pools** and Cloud Run **services**, so each Cloud Run instance reports a stable, recognizable identity to the Temporal service.

The primary API is `WorkerIdPlugin`. Register it once on your workflow client and it sets the client identity automatically; every worker created from that client inherits it. This mirrors the `CloudRunOpenTelemetryPlugin` in the companion `temporal-gcp-cloud-run` module.

> Experimental: Google Cloud Run support is experimental and may change without notice.

## Quick start

Add `temporal-gcp-cloud-run-worker-id` next to your Temporal SDK dependency, then register the plugin on the workflow client options:

```java
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.gcp.cloudrun.workerid.WorkerIdPlugin;
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

    // Workers created from this client inherit the identity the plugin set on the client. No
    // per-worker wiring needed.
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

The plugin then applies the metadata through the SDK's client plugin hook:

- **Client** (`configureWorkflowClient`): sets the client identity to `<instanceId>@<revision>` (falling back to `<instanceId>@<name>` and then the bare `<instanceId>`), but only when you have not already set an identity, so a user-provided identity always wins. The metadata is fetched here, once, and cached. Workers created from the client inherit this identity; the plugin sets nothing else on them.

Because the metadata server is only reachable from a Cloud Run instance, the plugin **fails fast**: the fetch in `configureWorkflowClient` throws `IllegalStateException` when the metadata server cannot be reached (which usually means the process is not running on Google Cloud Run). The plugin does not silently no-op off-platform.

## Reading the metadata directly

If you prefer to read the values yourself, or to fetch the metadata once and pass it in, use `GoogleCloudRunMetadata` directly:

```java
GoogleCloudRunMetadata metadata = GoogleCloudRunMetadata.fetch();
String identity = metadata.workerIdentity();

// Or hand the already-fetched metadata to the plugin to skip its own fetch:
WorkerIdPlugin plugin = new WorkerIdPlugin(metadata);
```

`GoogleCloudRunMetadata.fetch(String metadataUrl, Duration timeout)` overrides the metadata URL or the request timeout.

This module depends only on the Temporal SDK at compile time and uses the JDK's `HttpURLConnection` for the metadata request, so it adds no additional runtime dependencies.
