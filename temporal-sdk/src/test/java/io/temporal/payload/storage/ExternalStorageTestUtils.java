package io.temporal.payload.storage;

public final class ExternalStorageTestUtils {

  /** Turns on encoding of the references that replace stored payloads with the payload codec. */
  public static ExternalStorage.Builder encodingReferences(ExternalStorage.Builder builder) {
    return builder.setEncodeReferences(true);
  }

  private ExternalStorageTestUtils() {}
}
