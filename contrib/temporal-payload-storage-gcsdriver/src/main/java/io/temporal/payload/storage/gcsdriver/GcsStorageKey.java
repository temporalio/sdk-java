package io.temporal.payload.storage.gcsdriver;

import io.temporal.payload.storage.StorageDriverActivityInfo;
import io.temporal.payload.storage.StorageDriverTargetInfo;
import io.temporal.payload.storage.StorageDriverWorkflowInfo;
import java.nio.charset.StandardCharsets;

/** Builds versioned object names using the Go GCS driver's segment encoding. */
final class GcsStorageKey {
  private static final int MAX_NAME_BYTES = 1024;
  private static final String UNSAFE_CHARACTERS = "#[]*?:\"<>|/%";
  private static final char[] HEX = "0123456789ABCDEF".toCharArray();

  private GcsStorageKey() {}

  static String forPayload(StorageDriverTargetInfo target, String hexDigest) {
    String digest = "/d/sha256/" + hexDigest;
    String namespace;
    String name;
    if (target instanceof StorageDriverWorkflowInfo) {
      StorageDriverWorkflowInfo workflow = (StorageDriverWorkflowInfo) target;
      namespace = escapePathSegment(workflow.getNamespace());
      name =
          "v0/ns/"
              + namespace
              + "/wt/"
              + escapePathSegment(workflow.getType())
              + "/wi/"
              + escapePathSegment(workflow.getId())
              + "/ri/"
              + escapePathSegment(workflow.getRunId())
              + digest;
    } else if (target instanceof StorageDriverActivityInfo) {
      StorageDriverActivityInfo activity = (StorageDriverActivityInfo) target;
      namespace = escapePathSegment(activity.getNamespace());
      name =
          "v0/ns/"
              + namespace
              + "/at/"
              + escapePathSegment(activity.getType())
              + "/ai/"
              + escapePathSegment(activity.getId())
              + "/ri/"
              + escapePathSegment(activity.getRunId())
              + digest;
    } else {
      return "v0" + digest;
    }
    if (utf8Length(name) > MAX_NAME_BYTES) {
      name = "v0/ns/" + namespace + digest;
    }
    if (utf8Length(name) > MAX_NAME_BYTES) {
      throw new GcsStorageException("object name exceeds maximum 1024 UTF-8 bytes: " + name);
    }
    return name;
  }

  private static int utf8Length(String value) {
    return value.getBytes(StandardCharsets.UTF_8).length;
  }

  static String escapePathSegment(String value) {
    if (value == null || value.isEmpty()) {
      return "null";
    }
    if (value.equals(".")) {
      return "%2E";
    }
    if (value.equals("..")) {
      return "%2E%2E";
    }
    StringBuilder encoded = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (Character.isISOControl(c) || UNSAFE_CHARACTERS.indexOf(c) >= 0) {
        for (byte b : String.valueOf(c).getBytes(StandardCharsets.UTF_8)) {
          encoded.append('%').append(HEX[(b >> 4) & 0xf]).append(HEX[b & 0xf]);
        }
      } else {
        encoded.append(c);
      }
    }
    return encoded.toString();
  }
}
