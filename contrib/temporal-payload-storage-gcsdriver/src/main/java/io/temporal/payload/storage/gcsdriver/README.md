# Google Cloud Storage Driver

Temporal's experimental GCS Driver for External Storage. Uses the official
[Google Cloud Storage Java client](https://github.com/googleapis/java-storage).

## Usage

Construct the GCS storage driver:

```java
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import io.temporal.payload.storage.gcsdriver.GcsStorageDriver;
import io.temporal.payload.storage.gcsdriver.gcssdk.GcsStorageClientAdapter;

Storage storage =
  StorageOptions.grpc().setProjectId("my-project").build().getService();

GcsStorageClientAdapter storageClient =
  GcsStorageClientAdapter.newBuilder().setStorage(storage).build();

GcsStorageDriver driver =
  GcsStorageDriver.newBuilder()
    .setClient(storageClient)
    .setBucket("temporal-payloads")
    .build();
```

To choose a bucket per payload, use `setBucketResolver` instead of `setBucket`:

```java
GcsStorageDriver driver =
  GcsStorageDriver.newBuilder()
    .setClient(storageClient)
    .setBucketResolver((context, payload) -> "temporal-payloads-" + tenantOf(context))
    .build();
```

Register the driver in external storage config:

```java
import io.temporal.payload.storage.ExternalStorage;

ExternalStorage externalStorage =
  ExternalStorage.newBuilder()
    .setDriver(driver)
    .build();
```

The application owns the Google client; see [Configuration](#configuration).

## Configuration

The adapter runs each blocking GCS call on its own thread pool of up to 400 threads, matching the
worker's default 200 activity slots and 200 workflow task slots. Threads start on demand and stop
after a minute without work; calls beyond the limit wait in a queue. If you raise the worker's
slots, raise the pool with `setThreadPoolMaxSize(n)` to at least the activity slots plus workflow
task slots. A smaller pool queues storage calls and slows workflow tasks under load.

To use your own executor instead, such as `Executors.newVirtualThreadPerTaskExecutor()` on
Java 21+, call `setExecutor(...)`. Avoid an executor that rejects tasks, because a rejection fails
the store or retrieve. Avoid `CallerRunsPolicy`, because it runs the GCS call on the SDK's worker
thread and cancellation then interrupts that thread.

Worker slots (`setMaxConcurrentActivityExecutionSize` and
`setMaxConcurrentWorkflowTaskExecutionSize`) only loosely limit concurrent GCS calls. Each store or
retrieve sends one request per payload at the same time and holds all of those payloads in memory,
and the SDK can process several batches per task. Local activities, Nexus operations, and clients
also use the driver. Lower slots to reduce peak load and memory; raise the pool if calls queue.

After the worker factory terminates, close the adapter, which shuts down its thread pool, then
close the Google client. Shut down your own executor if you supplied one. Configure timeouts and
retry limits on the client; cancellation uses best-effort interruption.

Authenticate with
[Application Default Credentials or workload identity](https://docs.cloud.google.com/docs/authentication/provide-credentials-adc).
Create flat-namespace buckets first and grant `storage.objects.create` and `storage.objects.get`.

Hierarchical namespace (HNS) buckets are not currently supported. Their
[folder paths are limited to 512 UTF-8 bytes](https://docs.cloud.google.com/storage/docs/objects#naming),
but this driver's name fallback checks the 1,024-byte total object name limit.
Long workflow or activity identifiers can therefore produce names that HNS rejects.

Retain objects for as long as workflow histories may need them, including replay; see
[Temporal's lifecycle guidance](https://docs.temporal.io/external-storage#lifecycle-management-for-external-storage).

Use `setName(...)` for multiple drivers (default: `gcp.gcsdriver`). Keep names registered on clients
and workers while references exist. The upload threshold defaults to 256 KiB;
`setMaxPayloadSize(...)` limits serialized payloads to 50 MiB by default.

Use `StorageOptions.grpc()` when workers run in Google Cloud in the bucket's region. It selects
[DirectPath](https://docs.cloud.google.com/storage/docs/direct-connectivity) automatically, which
gives lower latency and higher throughput than HTTP. Outside Google Cloud, use
`StorageOptions.http()`; without DirectPath, gRPC uploads are much slower than HTTP under load.

The gRPC client exports its own metrics to Cloud Monitoring by default. Grant the worker's service
account `roles/monitoring.metricWriter`, or call `setEnableGrpcClientMetrics(false)` on
`StorageOptions.grpc()`, to avoid permission warnings in the logs.

For the simplest packaging, leave the Google client's dependency stack unshaded,
including `grpc-xds`, `grpc-googleapis`, and `conscrypt-openjdk-uber`. Preserve their
`META-INF/services` registrations and native libraries. If combining dependencies
into one jar, merge the service files and keep `org.conscrypt` unrelocated so
DirectPath providers and native crypto can load.

## Object names and integrity

Objects store the full Temporal payload, including metadata. Names include the namespace,
available workflow or activity identifiers, and a SHA-256 hash:

```text
v0/ns/{namespace}/wt/{workflow-type}/wi/{workflow-id}/ri/{run-id}/d/sha256/{hex-digest}
v0/ns/{namespace}/at/{activity-type}/ai/{activity-id}/ri/{run-id}/d/sha256/{hex-digest}
v0/d/sha256/{hex-digest}
```

The last form is used when no target is available. Empty or missing fields use `null`,
including an empty namespace on a workflow or activity target. Names preserve Unicode and
escape characters that interfere with [GCS tools](https://cloud.google.com/storage/docs/objects#naming),
including `/` and `%` within individual fields.

Names over 1,024 UTF-8 bytes fall back to `v0/ns/{namespace}/d/sha256/{hex-digest}`,
retaining the namespace for access policies. Identical payload bytes in the same context reuse an
object. The driver checks whether the object exists before uploading, so repeated payloads, such as
those stored again during replay, are not sent twice. Uploads never overwrite existing objects, and
downloads verify the hash before decoding.

References record `bucket`, `object_name`, `hash_algorithm`, and `hash_value`.
Downloads use the recorded bucket, so changing the upload bucket or resolver does not invalidate
earlier references.

## Testing

```bash
./gradlew :temporal-payload-storage-gcsdriver:test :temporal-payload-storage-gcsdriver-gcssdk:test
```

Gradle downloads and caches [fake-gcs-server](https://github.com/fsouza/fake-gcs-server)
under `~/.gradle/caches/fake-gcs-server`. JUnit starts it on a free local port and
cleans up the server and test buckets afterward.
