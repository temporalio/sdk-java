package io.temporal.internal.payload.storage;

import io.temporal.common.converter.DataConverterException;

/**
 * Signals that a payload marked as an external storage reference did not decode to one, which
 * happens when the payload codec that encoded it is not configured.
 */
public final class UndecodableExternalStorageReferenceException extends DataConverterException {
  public UndecodableExternalStorageReferenceException() {
    super(
        "[TMPRL1106] Encountered an encoded external storage reference that could not be "
            + "decoded. Check that the payload codec used to write it is configured.");
  }
}
