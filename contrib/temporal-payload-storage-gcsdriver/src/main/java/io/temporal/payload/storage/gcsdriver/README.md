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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

Storage storage =
  StorageOptions.grpc().setProjectId("my-project").build().getService();

ExecutorService storageExecutor = new ThreadPoolExecutor(
  4, 4, 0L, TimeUnit.MILLISECONDS,
  new ArrayBlockingQueue<Runnable>(64),
  new ThreadPoolExecutor.AbortPolicy());

GcsStorageDriver driver =
  GcsStorageDriver.newBuilder()
    .setClient(new GcsStorageClientAdapter(storage, storageExecutor))
    .setBucket("temporal-payloads")
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

The application owns the Google client and executor; see [Configuration](#configuration).

## Configuration

Use a dedicated executor with bounded threads and queue capacity and an abort rejection policy.
The pool sizes above are examples. Shut down the executor and close the Google client after
Temporal's storage work finishes. Configure timeouts and retry limits on the client;
cancellation uses best-effort interruption.

Authenticate with
[Application Default Credentials or workload identity](https://docs.cloud.google.com/docs/authentication/provide-credentials-adc).
Create a flat-namespace bucket first and grant `storage.objects.create` and `storage.objects.get`.
Retain objects for as long as workflow histories may need them, including replay; see
[Temporal's lifecycle guidance](https://docs.temporal.io/external-storage#lifecycle-management-for-external-storage).

Use `setName(...)` for multiple drivers (default: `gcp.gcsdriver`). Keep names registered on clients
and workers while references exist. The upload threshold defaults to 256 KiB;
`setMaxPayloadSize(...)` limits serialized payloads to 50 MiB by default.

`StorageOptions.grpc()` enables automatic [DirectPath](https://docs.cloud.google.com/storage/docs/direct-connectivity)
selection in supported Google Cloud environments.

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
object. Uploads never overwrite existing objects, and downloads verify the hash before decoding.

References record `bucket`, `object_name`, `hash_algorithm`, and `hash_value`.
Downloads use the recorded bucket, so changing the upload bucket does not invalidate earlier references.

## Testing

```bash
./gradlew :temporal-payload-storage-gcsdriver:test :temporal-payload-storage-gcsdriver-gcssdk:test
```

Gradle downloads and caches [fake-gcs-server](https://github.com/fsouza/fake-gcs-server)
under `~/.gradle/caches/fake-gcs-server`. JUnit starts it on a free local port and
cleans up the server and test buckets afterward.
