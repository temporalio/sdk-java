package io.temporal.internal.client;

import com.google.common.base.Strings;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.converter.DataConverter;
import io.temporal.internal.common.converter.TemporalTransferTypeDataConverter;
import io.temporal.internal.payload.storage.ExternalStorageDataConverter;
import io.temporal.internal.payload.storage.ExternalStorageRunner;
import io.temporal.payload.context.WorkflowSerializationContext;
import io.temporal.payload.storage.StorageDriverWorkflowInfo;
import java.util.Objects;
import javax.annotation.Nullable;

/** Assembles client converters and scopes workflow conversion when workflow identity is known. */
public final class ClientDataConverterFactory {

  private final String namespace;
  private final ExternalStorageDataConverter workflowConverter;

  ClientDataConverterFactory(
      WorkflowClientOptions clientOptions, @Nullable ExternalStorageRunner externalStorage) {
    this.namespace = clientOptions.getNamespace();
    // Workflow reads must reject storage references even when no driver is configured.
    this.workflowConverter = withStorage(clientOptions.getDataConverter(), externalStorage);
  }

  /** Returns a transfer-aware client converter without external storage. */
  public static DataConverter forClient(DataConverter configured) {
    return transferAware(configured);
  }

  /** Returns a client converter with external storage outside transfer conversion. */
  public static DataConverter forClient(
      DataConverter configured, ExternalStorageRunner externalStorage) {
    return withStorage(configured, Objects.requireNonNull(externalStorage, "externalStorage"));
  }

  private static ExternalStorageDataConverter withStorage(
      DataConverter configured, @Nullable ExternalStorageRunner externalStorage) {
    return new ExternalStorageDataConverter(transferAware(configured), externalStorage);
  }

  private static DataConverter transferAware(DataConverter configured) {
    return TemporalTransferTypeDataConverter.wrap(configured);
  }

  DataConverter forWorkflow(
      String workflowId, @Nullable String runId, @Nullable String workflowType) {
    DataConverter converter =
        workflowConverter.withContext(new WorkflowSerializationContext(namespace, workflowId));
    return ((ExternalStorageDataConverter) converter)
        .withStorageTarget(
            new StorageDriverWorkflowInfo(
                namespace,
                Strings.emptyToNull(workflowId),
                Strings.emptyToNull(runId),
                Strings.emptyToNull(workflowType)));
  }
}
