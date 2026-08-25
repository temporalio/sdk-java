package io.temporal.internal.payload.storage;

import io.temporal.common.converter.DataConverterException;

/**
 * Signals that an external storage reference reached ordinary payload conversion without being
 * handled by any external storage integration.
 */
public final class ExternalStorageUnhandledReferenceException extends DataConverterException {
  public ExternalStorageUnhandledReferenceException() {
    super(
        "[BUG] An external storage reference reached payload conversion without being handled. This"
            + "is likely an SDK bug. Please file a bug report.");
  }
}
