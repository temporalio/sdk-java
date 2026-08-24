package io.temporal.internal.payload.storage;

import io.temporal.common.converter.DataConverterException;

/**
 * Signals that an external storage reference reached a data converter without storage configured.
 * Logs a TMPRL1105 error.
 */
public final class ExternalStorageNotConfiguredException extends DataConverterException {
  public ExternalStorageNotConfiguredException() {
    super(
        "[TMPRL1105] Encountered an external-storage reference payload but external storage is not "
            + "configured. Configure external storage on the DataConverter, for example with "
            + "DefaultDataConverter.withExternalStorage(...), and provide a driver able to "
            + "retrieve it.");
  }
}
