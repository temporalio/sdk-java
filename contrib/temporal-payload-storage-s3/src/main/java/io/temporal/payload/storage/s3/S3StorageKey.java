package io.temporal.payload.storage.s3;

import io.temporal.payload.storage.StorageDriverActivityInfo;
import io.temporal.payload.storage.StorageDriverTargetInfo;
import io.temporal.payload.storage.StorageDriverWorkflowInfo;
import java.nio.charset.StandardCharsets;

/**
 * Builds the content-addressable S3 object key. The key format and percent-encoding rules are the
 * cross-SDK specification documented in this package's {@code README.md}.
 */
final class S3StorageKey {
  private static final String KEY_VERSION = "v0";
  private static final String PATH_SEGMENT_UNRESERVED = "-_.~$&+:=@";

  private S3StorageKey() {}

  static String forPayload(StorageDriverTargetInfo target, String hashAlgorithm, String hexDigest) {
    String digestSegment = "/d/" + hashAlgorithm + "/" + hexDigest;
    if (target instanceof StorageDriverWorkflowInfo) {
      StorageDriverWorkflowInfo wf = (StorageDriverWorkflowInfo) target;
      return KEY_VERSION
          + "/ns/"
          + escapePathSegment(wf.getNamespace())
          + "/wt/"
          + escapePathSegment(wf.getType())
          + "/wi/"
          + escapePathSegment(wf.getId())
          + "/ri/"
          + escapePathSegment(wf.getRunId())
          + digestSegment;
    }
    if (target instanceof StorageDriverActivityInfo) {
      StorageDriverActivityInfo act = (StorageDriverActivityInfo) target;
      return KEY_VERSION
          + "/ns/"
          + escapePathSegment(act.getNamespace())
          + "/at/"
          + escapePathSegment(act.getType())
          + "/ai/"
          + escapePathSegment(act.getId())
          + "/ri/"
          + escapePathSegment(act.getRunId())
          + digestSegment;
    }
    return KEY_VERSION + digestSegment;
  }

  static String escapePathSegment(String value) {
    if (value == null || value.isEmpty()) {
      return "null";
    }
    StringBuilder sb = new StringBuilder(value.length());
    for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
      int c = b & 0xFF;
      if ((c >= 'A' && c <= 'Z')
          || (c >= 'a' && c <= 'z')
          || (c >= '0' && c <= '9')
          || PATH_SEGMENT_UNRESERVED.indexOf(c) >= 0) {
        sb.append((char) c);
      } else {
        sb.append('%');
        sb.append(Character.toUpperCase(Character.forDigit((c >> 4) & 0xF, 16)));
        sb.append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
      }
    }
    return sb.toString();
  }
}
