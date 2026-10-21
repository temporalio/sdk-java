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
  StorageOptions.newBuilder().setProjectId("my-project").build().getService();

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
The identity needs object creation and read permissions. Retain objects for the workflow lifetime
plus history retention, including replay; see
[Temporal's lifecycle guidance](https://docs.temporal.io/external-storage#lifecycle-management-for-external-storage).

Use `setName(...)` for multiple drivers (default: `gcp.gcsdriver`). Keep names registered on clients
and workers while references exist. `setMaxPayloadSize(...)` defaults to 50 MiB.

## GCS Storage Key Specification

Object names follow the Go GCS driver's format. Objects contain the complete serialized Temporal
`Payload` protobuf, including metadata. Writes create objects only if absent and never overwrite
existing contents. Duplicate writes still attempt an upload.

### Key format

Workflow key:
```text
v0/ns/{namespace}/wt/{workflow-type}/wi/{workflow-id}/ri/{run-id}/d/sha256/{hex-digest}
```

Activity key:
```text
v0/ns/{namespace}/at/{activity-type}/ai/{activity-id}/ri/{run-id}/d/sha256/{hex-digest}
```

Fallback key (unknown target):
```text
v0/d/sha256/{hex-digest}
```

- Dynamic path segments are percent-encoded (rules below).
- Missing or empty values, including a missing `run-id`, are encoded as `null`.
- `hex-digest` is lower-case SHA-256 hex (64 characters) of the exact serialized bytes.
- Logically equivalent protobufs serialized by different SDKs need not produce identical keys.

Names longer than 1,024 UTF-8 bytes fall back to `v0/ns/{namespace}/d/sha256/{hex-digest}`.
If that still exceeds the limit, the batch fails before uploading. Use flat namespace buckets;
hierarchical namespace buckets have different path limits.

### Percent-encoding rules

Segments percent-encode C0, DEL, and C1 control characters and `#[]*?:"<>|/%` as uppercase `%XX`
UTF-8 byte escapes. Other Unicode, spaces, `+`, and `=` remain literal. Literal `.` and `..`
segments become `%2E` and `%2E%2E`.

### Examples

Workflow key example:
```text
input:
  namespace=payments prod
  workflow-type=Charge/Workflow
  workflow-id=order+123=abc
  run-id=run-123

output:
  v0/ns/payments prod/wt/Charge%2FWorkflow/wi/order+123=abc/ri/run-123/d/sha256/{hex-digest}
```

Activity key example:
```text
input:
  namespace=payments prod
  activity-type=Capture/Charge
  activity-id=activity id+42
  run-id=run-456

output:
  v0/ns/payments prod/at/Capture%2FCharge/ai/activity id+42/ri/run-456/d/sha256/{hex-digest}
```

## Claims

| Field | Value |
| --- | --- |
| `bucket` | Actual upload bucket. |
| `object_name` | Complete object name. |
| `hash_algorithm` | `sha256`. |
| `hash_value` | Lower-case SHA-256 hex digest of the stored bytes. |

Reads require `object_name` and ignore unknown fields. Downloads
use the bucket recorded in the claim, so changing the upload bucket does not invalidate earlier
references. Every download verifies SHA-256 before parsing the payload.
