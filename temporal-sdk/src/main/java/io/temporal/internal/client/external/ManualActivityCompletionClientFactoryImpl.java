package io.temporal.internal.client.external;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.uber.m3.tally.Scope;
import io.temporal.activity.ManualActivityCompletionClient;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.common.converter.DataConverter;
import io.temporal.internal.payload.storage.ExternalStorageMessageConverter;
import io.temporal.payload.context.ActivitySerializationContext;
import io.temporal.payload.storage.StorageDriverActivityInfo;
import io.temporal.payload.storage.StorageDriverTargetInfo;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

class ManualActivityCompletionClientFactoryImpl implements ManualActivityCompletionClientFactory {
  private final WorkflowServiceStubs service;
  private final DataConverter dataConverter;
  private final String namespace;
  private final String identity;
  private final @Nullable ExternalStorageMessageConverter externalStorage;

  ManualActivityCompletionClientFactoryImpl(
      @Nonnull WorkflowServiceStubs service,
      @Nonnull String namespace,
      @Nonnull String identity,
      @Nonnull DataConverter dataConverter,
      @Nullable ExternalStorageMessageConverter externalStorage) {
    this.service = Objects.requireNonNull(service);
    this.namespace = Objects.requireNonNull(namespace);
    this.identity = Objects.requireNonNull(identity);
    this.dataConverter = Objects.requireNonNull(dataConverter);
    this.externalStorage = externalStorage;
  }

  @Override
  public ManualActivityCompletionClient getClient(
      @Nonnull byte[] taskToken, @Nonnull Scope metricsScope) {
    return getClient(taskToken, metricsScope, null);
  }

  @Override
  public ManualActivityCompletionClient getClient(
      @Nonnull byte[] taskToken,
      @Nonnull Scope metricsScope,
      @Nullable ActivitySerializationContext activitySerializationContext) {
    StorageDriverTargetInfo storageTarget =
        activitySerializationContext == null
            ? null
            : new StorageDriverActivityInfo(
                namespace,
                null,
                null,
                Strings.emptyToNull(activitySerializationContext.getActivityType()));
    return getClient(taskToken, metricsScope, activitySerializationContext, storageTarget);
  }

  @Override
  public ManualActivityCompletionClient getClient(
      @Nonnull byte[] taskToken,
      @Nonnull Scope metricsScope,
      @Nullable ActivitySerializationContext activitySerializationContext,
      @Nullable StorageDriverTargetInfo storageTarget) {
    Preconditions.checkNotNull(metricsScope, "metricsScope");
    Preconditions.checkNotNull(taskToken, "taskToken");
    Preconditions.checkArgument(taskToken.length > 0, "empty taskToken");
    return new ManualActivityCompletionClientImpl(
        service,
        namespace,
        identity,
        dataConverter,
        metricsScope,
        taskToken,
        null,
        null,
        activitySerializationContext,
        storageTarget,
        externalStorage);
  }

  @Override
  public ManualActivityCompletionClient getClient(
      @Nonnull WorkflowExecution execution,
      @Nonnull String activityId,
      @Nonnull Scope metricsScope) {
    return getClient(execution, activityId, metricsScope, null);
  }

  @Override
  public ManualActivityCompletionClient getClient(
      @Nonnull WorkflowExecution execution,
      @Nonnull String activityId,
      @Nonnull Scope metricsScope,
      @Nullable ActivitySerializationContext activitySerializationContext) {
    Preconditions.checkNotNull(metricsScope, "metricsScope");
    Preconditions.checkNotNull(execution, "execution");
    Preconditions.checkNotNull(activityId, "activityId");
    String activityRunId =
        execution.getWorkflowId().isEmpty() ? Strings.emptyToNull(execution.getRunId()) : null;
    String activityType =
        activitySerializationContext == null
            ? null
            : Strings.emptyToNull(activitySerializationContext.getActivityType());
    return new ManualActivityCompletionClientImpl(
        service,
        namespace,
        identity,
        dataConverter,
        metricsScope,
        null,
        execution,
        activityId,
        activitySerializationContext,
        new StorageDriverActivityInfo(namespace, activityId, activityRunId, activityType),
        externalStorage);
  }
}
