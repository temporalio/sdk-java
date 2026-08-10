package io.temporal.internal.payload.storage;

import io.temporal.common.converter.DataConverterException;

/**
 * Signals that an external-storage reference reached a data converter without storage configured.
 */
public final class ExternalStorageNotConfiguredException extends DataConverterException {
  public ExternalStorageNotConfiguredException() {
    super(
        "[TMPRL1105] Encountered an external-storage reference payload but external storage is not "
            + "configured. Configure WorkflowClientOptions.Builder.setExternalStorage(...) with a "
            + "driver able to retrieve it.");
  }
}
