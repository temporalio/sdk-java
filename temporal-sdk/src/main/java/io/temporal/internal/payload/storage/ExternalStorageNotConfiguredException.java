package io.temporal.internal.payload.storage;

import io.temporal.common.converter.DataConverterException;
import javax.annotation.Nullable;

/**
 * Signals that an external-storage reference reached a data converter without storage configured.
 */
public final class ExternalStorageNotConfiguredException extends DataConverterException {
  public ExternalStorageNotConfiguredException() {
    super(
        "[TMPRL-1105] Encountered an external-storage reference payload but external storage is not "
            + "configured. Configure WorkflowClientOptions.Builder.setExternalStorage(...) with a "
            + "driver able to retrieve it.");
  }

  /** Returns the missing-storage failure from a wrapper chain, if present. */
  @Nullable
  public static ExternalStorageNotConfiguredException find(Throwable failure) {
    while (failure != null) {
      if (failure instanceof ExternalStorageNotConfiguredException) {
        return (ExternalStorageNotConfiguredException) failure;
      }
      Throwable cause = failure.getCause();
      if (cause == failure) {
        break;
      }
      failure = cause;
    }
    return null;
  }
}
