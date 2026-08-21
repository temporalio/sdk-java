# Temporal Google Cloud Run support

This module derives a Temporal worker identity and a `WorkerDeploymentVersion` from Google Cloud Run instance metadata, for both Cloud Run **worker pools** and Cloud Run **services**.

Cloud Run runs a long-lived container, so there is no per-request handler to wrap. This module is a small metadata helper rather than a worker wrapper: fetch the metadata once during startup and apply it to your client and worker option builders.

> Experimental: Google Cloud Run support is experimental and may change without notice.

## Quick start

Add `temporal-gcp-cloud-run` next to your Temporal SDK dependency, then fetch the metadata and apply it while the worker starts up:

```java
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.gcp.cloudrun.GoogleCloudRunMetadata;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerOptions;

public final class Main {
  public static void main(String[] args) {
    // Read Cloud Run instance metadata once during startup.
    GoogleCloudRunMetadata metadata = GoogleCloudRunMetadata.fetch();

    WorkflowServiceStubs service =
        WorkflowServiceStubs.newServiceStubs(
            WorkflowServiceStubsOptions.newBuilder()
                .setTarget("my-namespace.tmprl.cloud:7233")
                .build());

    // applyTo(...) sets the derived worker identity on the client options.
    WorkflowClient client =
        WorkflowClient.newInstance(
            service, metadata.applyTo(WorkflowClientOptions.newBuilder()).build());

    WorkerFactory factory = WorkerFactory.newInstance(client);

    // applyTo(...) sets the deployment version and enables worker versioning on the worker options.
    WorkerOptions workerOptions = metadata.applyTo(WorkerOptions.newBuilder()).build();

    Worker worker = factory.newWorker("orders", workerOptions);
    worker.registerWorkflowImplementationTypes(OrderWorkflowImpl.class);
    worker.registerActivitiesImplementations(new OrderActivitiesImpl());

    factory.start();
  }
}
```

Both `applyTo(...)` methods return the builder they were given, so they compose with the rest of your builder configuration.

## How it works

`GoogleCloudRunMetadata.fetch()` resolves three values:

- **name** (the Temporal deployment name): the first non-empty of `CLOUD_RUN_WORKER_POOL` (set on Cloud Run worker pools) then `K_SERVICE` (set on Cloud Run services).
- **revision**: the first non-empty of `CLOUD_RUN_REVISION` (worker pools) then `K_REVISION` (services).
- **instanceId**: read from the Cloud Run metadata server with a single HTTP `GET` to `http://metadata.google.internal/computeMetadata/v1/instance/id` with the required `Metadata-Flavor: Google` header. The metadata server is available on both worker pools and services.

Worker pools receive `CLOUD_RUN_WORKER_POOL` and `CLOUD_RUN_REVISION` and no `K_*` variables, while services receive `K_SERVICE` and `K_REVISION`, so resolving each value from the worker-pool variable first and the service variable second supports both.

`workerIdentity()` returns `<instanceId>@<revision>`, falling back to `<instanceId>@<name>` and then to the bare `<instanceId>` when those values are blank. `workerDeploymentVersion()` maps the name to the deployment name and the revision to the build id, so each Cloud Run revision becomes a distinct `WorkerDeploymentVersion`.

The two `applyTo(...)` overloads mirror the SDK's "apply defaults to your options" idiom: `applyTo(WorkflowClientOptions.Builder)` sets the worker identity on the client side, and `applyTo(WorkerOptions.Builder)` sets the deployment version (with versioning enabled) on the worker side. Each returns the builder for chaining. If you prefer to read the values yourself, call `workerIdentity()` and `workerDeploymentVersion()` directly.

Because the metadata server is only reachable from a Cloud Run instance, `fetch()` throws `IllegalStateException` when it cannot be reached, and `workerDeploymentVersion()` (and therefore `applyTo(WorkerOptions.Builder)`) throws `IllegalStateException` when the name or revision is not set. Use `GoogleCloudRunMetadata.fetch(String metadataUrl, Duration timeout)` to override the metadata URL or the request timeout.

This module depends only on the Temporal SDK at compile time and uses the JDK's `HttpURLConnection` for the metadata request, so it adds no additional runtime dependencies.
