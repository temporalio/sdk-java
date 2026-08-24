package io.temporal.internal.payload.storage;

import io.temporal.common.converter.DataConverterException;

/**
 * Signals that a payload referenced in external storage needs to be retrieved, but external storage
 * is not configured. Logs a TMPRL1105 error.
 */
public final class ExternalStorageNotConfiguredException extends DataConverterException {
  public ExternalStorageNotConfiguredException() {
    super(
        "[TMPRL1105] Encountered a reference to a payload in external storage, but no external "
            + "storage is configured to retrieve it. Configure external storage on the "
            + "DataConverter with "
            + "DefaultDataConverter.withExternalStorage(...) and provide a driver able to "
            + "retrieve it.");
  }
}
