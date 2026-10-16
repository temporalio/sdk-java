# Google Cloud Storage payload driver

Experimental external payload storage for Temporal, with a provider-independent driver and an
optional adapter for the official Google Cloud Storage Java client. Production code targets Java 8.
The application supplies an existing flat namespace bucket, a configured Google `Storage` client,
and an executor. No bucket, credentials, or threads are created by the driver or adapter.

## Configuration

These artifacts are versioned with the SDK and must be specified explicitly; the Temporal BOM does
not manage storage artifacts. The following example uses the upcoming snapshot containing the
driver; use the matching version you build or publish.

```groovy
dependencies {
    implementation 'io.temporal:temporal-sdk:1.41.0-SNAPSHOT'
    implementation 'io.temporal:temporal-payload-storage-gcsdriver:1.41.0-SNAPSHOT'
    implementation 'io.temporal:temporal-payload-storage-gcsdriver-gcssdk:1.41.0-SNAPSHOT'
}
```

```java
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.payload.storage.ExternalStorage;
import io.temporal.payload.storage.StorageDriver;
import io.temporal.payload.storage.gcsdriver.GcsStorageDriver;
import io.temporal.payload.storage.gcsdriver.gcssdk.GcsStorageClientAdapter;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

Storage storage = StorageOptions.newBuilder()
    .setProjectId("my-project")
    .build()
    .getService();

ExecutorService storageExecutor = new ThreadPoolExecutor(
    4, 4, 0L, TimeUnit.MILLISECONDS,
    new ArrayBlockingQueue<Runnable>(64),
    new ThreadPoolExecutor.AbortPolicy());

StorageDriver driver = GcsStorageDriver.newBuilder()
    .setClient(new GcsStorageClientAdapter(storage, storageExecutor))
    .setBucket("temporal-payloads")
    .build();

ExternalStorage externalStorage = ExternalStorage.newBuilder()
    .setDriver(driver)
    .build();

WorkflowClientOptions options = WorkflowClientOptions.newBuilder()
    .setExternalStorage(externalStorage)
    .build();
```

Register compatible named drivers on every client and worker that reads or writes these references.
The driver name and type default to `gcp.gcsdriver`. Use `setName` for multiple bucket configurations
and the existing driver selector to route writes. Names must be nonempty and remain registered
while references exist. Downloads use the bucket recorded in each claim, so changing the upload
bucket does not invalidate old references. The SDK's external storage threshold remains 256 KiB;
`setMaxPayloadSize` independently limits each serialized upload to 50 MiB by default.

The adapter uses `google-cloud-storage:2.75.0` only in the optional adapter module. Its transitive
dependencies can upgrade shared libraries, including protobuf to 4.33.6 and gRPC to 1.83.0, when
combined with Temporal's dependencies. Review your application's resolved dependency graph. The
driver module has no Google Cloud client dependency, and accepts custom `GcsStorageDriverClient`
implementations.

## Executor and client ownership

Pool and queue sizes above are examples. Use a dedicated executor with bounded threads and queue
capacity sized for your batches and payload sizes. Rejection completes the operation exceptionally.
Use an abort policy; caller-runs execution is rejected before any Google I/O. Discard policies are
unsuitable because they can silently leave futures incomplete.

The application owns the executor and Google client. Stop Temporal workers and clients and let
storage operations finish before shutting down the executor and closing `Storage`. The adapter
has no independent lifecycle. Configure transport, retries, operation timeouts, and retry deadlines
on the Google client; the driver adds no retry loop and uses no common fork-join pool.

Cancelling the batch future or its context token cancels outstanding provider futures. The adapter
cancels its executor tasks with interruption, including queued work. Interruption is best effort;
Google client time limits bound work that continues after interruption. Cancellation cannot undo
completed uploads. Failed batches return no partial results, but already uploaded objects can
remain unreferenced.

Payloads and downloads are materialized in memory. Size the executor and queue accordingly. The
upload size limit does not impose a streaming download limit.

## Credentials and retention

Configure production authentication on the Google client, normally using
[Application Default Credentials or workload identity](https://docs.cloud.google.com/docs/authentication/provide-credentials-adc).
A logged-in `gcloud` CLI and its default project do not establish Java ADC configuration. The driver
does not run `gcloud` or create service-account keys. The runtime identity needs object creation and
reading in the referenced buckets; bucket administration and object deletion are outside its contract.

Configure a bucket lifecycle policy that retains objects for the workflow lifetime plus history
retention, including replay needs. See [Temporal's external storage lifecycle guidance](https://docs.temporal.io/external-storage#lifecycle-management-for-external-storage).
Existing Temporal codecs can compress or encrypt payloads before storage.

## Contents, claims, and naming

Objects contain the complete serialized Temporal `Payload` protobuf, including metadata. Uploads
use generation-zero conditional creation with no existence request. Only a `412` from this
conditional create counts as successful deduplication; other errors fail the operation. Identical
serialized bytes in the same target scope share an object name. Duplicate writes still attempt an
upload and do not promise to save upload bandwidth. Existing objects are never overwritten.

Each claim has these string fields:

| Field | Meaning |
| --- | --- |
| `bucket` | Actual upload bucket. |
| `object_name` | Complete object name. |
| `key` | Same value as `object_name`, for early Go GCS claim compatibility. |
| `hash_algorithm` | `sha256`. |
| `hash_value` | Lowercase SHA-256 hex digest of the exact stored bytes. |

Reads accept either name field, reject conflicting fields, and ignore unknown fields. SHA-256 is
verified on every download before protobuf parsing. Missing objects, permissions failures, invalid
claims, corrupted bytes, and invalid protobufs are errors. External overwrites are detected by the
checksum. Logically equivalent protobufs serialized by different SDKs need not produce identical
bytes or object names. The SDK's reference envelope is unchanged.

Object names follow the Go GCS driver format:

```text
Workflow: v0/ns/{namespace}/wt/{workflow-type}/wi/{workflow-id}/ri/{run-id}/d/sha256/{digest}
Activity: v0/ns/{namespace}/at/{activity-type}/ai/{activity-id}/ri/{run-id}/d/sha256/{digest}
Unknown:  v0/d/sha256/{digest}
```

Segments percent-encode C0, DEL, and C1 control characters and `#[]*?:"<>|/%` using uppercase UTF-8
byte escapes. Other Unicode, spaces, `+`, and `=` remain literal. Missing or empty segments become
`null`; literal `.` and `..` become `%2E` and `%2E%2E`. For example, workflow type `my/workflow`
becomes `my%2Fworkflow`, while workflow ID `wf id+1` stays unchanged.

Names longer than 1,024 UTF-8 bytes fall back to `v0/ns/{namespace}/d/sha256/{digest}` to preserve
namespace scope. If that still exceeds the limit, the batch fails before uploading. This version
supports flat namespace buckets; hierarchical namespace buckets have different path limits.
