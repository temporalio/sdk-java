package io.temporal.internal.client;

import com.google.common.base.Strings;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.converter.DataConverter;
import io.temporal.internal.payload.storage.ExternalStorageDataConverter;
import io.temporal.internal.payload.storage.ExternalStorageRunner;
import io.temporal.payload.context.WorkflowSerializationContext;
import io.temporal.payload.storage.StorageDriverWorkflowInfo;
import javax.annotation.Nullable;

/** Supplies a {@link DataConverter} for clients. */
final class WorkflowClientDataConverterFactory {

  private final String namespace;
  private final DataConverter baseConverter;

  WorkflowClientDataConverterFactory(
      WorkflowClientOptions clientOptions, @Nullable ExternalStorageRunner externalStorage) {
    this.namespace = clientOptions.getNamespace();
    this.baseConverter =
        new ExternalStorageDataConverter(clientOptions.getDataConverter(), externalStorage);
  }

  DataConverter forWorkflow(
      String workflowId, @Nullable String runId, @Nullable String workflowType) {
    DataConverter converter =
        baseConverter.withContext(new WorkflowSerializationContext(namespace, workflowId));
    return ((ExternalStorageDataConverter) converter)
        .withStorageTarget(
            new StorageDriverWorkflowInfo(
                namespace,
                Strings.emptyToNull(workflowId),
                Strings.emptyToNull(runId),
                Strings.emptyToNull(workflowType)));
  }
}
