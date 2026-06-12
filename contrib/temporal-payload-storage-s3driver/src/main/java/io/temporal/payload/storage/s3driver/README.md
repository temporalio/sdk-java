# AWS S3 Driver

Temporal's S3 Driver for External Storage. Uses the official [AWS S3 Java SDK](https://github.com/aws/aws-sdk-java-v2).

## Usage

Construct the S3 storage driver:

```java
import io.temporal.payload.storage.s3driver.S3StorageDriver;
import io.temporal.payload.storage.s3driver.awssdkv2.S3AsyncClientAdapter;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;

S3AsyncClient s3Client =
  S3AsyncClient.builder().region(Region.US_EAST_1).build();

S3StorageDriver driver =
  S3StorageDriver.newBuilder()
    .setClient(new S3AsyncClientAdapter(s3Client))
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

Use `setBucketResolver(...)` instead of `setBucket(...)` when bucket selection must vary per
payload.

## S3 Storage Key Specification

All Temporal S3 drivers generate S3 keys in a consistent manner.

### Key format

Workflow key:
```text
v0/ns/{namespace}/wt/{workflow-type}/wi/{workflow-id}/ri/{run-id}/d/{hash-algorithm}/{hex-digest}
```

Activity key:
```text
v0/ns/{namespace}/at/{activity-type}/ai/{activity-id}/ri/{run-id}/d/{hash-algorithm}/{hex-digest}
```

Fallback key (unknown target):
```text
v0/d/{hash-algorithm}/{hex-digest}
```

- If no namespace, workflow, or activity information is available, the fallback is used.
- Dynamic path segments are percent-encoded (rules below).
- Missing values (including a missing `run-id`) are encoded as `null`.
- `hex-digest` is lower-case SHA-256 hex (64 characters).

### Percent-encoding rules

1. Treat each key path component as UTF-8 bytes.
2. Leave ASCII letters and digits unescaped.
3. Leave `-`, `_`, and `.` unescaped. These are the only punctuation characters that are both in the
   RFC 3986 unreserved set and in the set AWS documents as safe for S3 object keys. Everything else,
   including `~`, is escaped.
4. Encode all other bytes as `%` followed by two uppercase hexadecimal digits.
5. Empty or null values are encoded as the literal string `null`.

### Examples

Workflow key example:

```text
input:
  namespace=payments prod
  workflow-type=ChargeWorkflow
  workflow-id=order+123=abc
  run-id=3f1d6c7a-8b2e-4f7a-9d0a-87a6f95e4d31
  hash-algorithm=sha256
  hex-digest=9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08

output:
  v0/ns/payments%20prod/wt/ChargeWorkflow/wi/order%2B123%3Dabc/ri/3f1d6c7a-8b2e-4f7a-9d0a-87a6f95e4d31/d/sha256/9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08
```

Activity key example:

```text
input:
  namespace=payments prod
  activity-type=Capture/Charge
  activity-id=activity id+42
  run-id=9e1d1fd9-2f8a-4c40-93e2-731f31b9268b
  hash-algorithm=sha256
  hex-digest=2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824

output:
  v0/ns/payments%20prod/at/Capture%2FCharge/ai/activity%20id%2B42/ri/9e1d1fd9-2f8a-4c40-93e2-731f31b9268b/d/sha256/2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824
```

Fallback key example:

```text
input:
  hash-algorithm=sha256
  hex-digest=486ea46224d1bb4fb680f34f7c9ad96a8f24ec88be73ea8e5a6c65260e9cb8a7

output:
  v0/d/sha256/486ea46224d1bb4fb680f34f7c9ad96a8f24ec88be73ea8e5a6c65260e9cb8a7
```
