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

### gRPC and DirectPath

The example above supplies a gRPC client to the adapter. The HTTP client created
by `StorageOptions.newBuilder()` does not use DirectPath.
Google's gRPC client automatically attempts DirectPath on Compute Engine, including
GKE nodes. The VM must have an attached service account, the bucket must overlap
the VM's region, and the network must allow the DirectPath endpoints and backend
address ranges. See Google's [gRPC setup](https://docs.cloud.google.com/storage/docs/enable-grpc-api)
and [direct connectivity requirements](https://docs.cloud.google.com/storage/docs/direct-connectivity).
The adapter preserves the supplied client's transport configuration.

The GCS artifacts are ordinary jars and leave the Google client and its dependencies
unshaded. Applications that build a shaded jar must merge `META-INF/services` entries
so the `google-c2p` and xDS resolvers and load balancers remain registered. Keep
`org.conscrypt` unrelocated and retain its native libraries: Conscrypt's JNI code
looks up classes by their original names. The Google client already supplies
Conscrypt as a transitive dependency. For a shaded application, verify ALTS and
DirectPath backend addresses against its final artifact as well.

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

For gRPC coverage, start [storage-testbench](https://github.com/googleapis/storage-testbench#how-to-use-this-testbench)
and its gRPC server, then run:

```bash
TEMPORAL_GCS_TESTBENCH_ENDPOINT=http://127.0.0.1:8888 \
  ./gradlew :temporal-payload-storage-gcsdriver-gcssdk:test \
  --tests '*GcsStorageClientAdapterGrpcIntegrationTest' --rerun-tasks
```

To verify DirectPath on a Compute Engine VM, use Application Default Credentials and
an existing throwaway bucket in the VM's region instead:

```bash
TEMPORAL_GCS_DIRECTPATH_BUCKET=my-test-bucket \
  ./gradlew :temporal-payload-storage-gcsdriver-gcssdk:test \
  --tests '*GcsStorageClientAdapterGrpcIntegrationTest' --rerun-tasks
```

The live test requires object create, get, list, and delete permissions. It verifies
successful upload and download streams use ALTS and DirectPath backend addresses,
so a successful transfer over a fallback connection fails the test. Both modes
check payload integrity, conditional creation, concurrent uploads, and missing objects.
Each test deletes objects under its own unique prefix. Without either environment
variable, these gRPC tests are skipped.
