package io.temporal.internal.client.external;

import com.uber.m3.tally.Scope;
import io.temporal.activity.ManualActivityCompletionClient;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.common.converter.DataConverter;
import io.temporal.payload.context.ActivitySerializationContext;
import io.temporal.serviceclient.WorkflowServiceStubs;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface ManualActivityCompletionClientFactory {

  /**
   * Create a {@link ManualActivityCompletionClientFactory} that emits simple {@link
   * ManualActivityCompletionClientImpl} implementations
   */
  static ManualActivityCompletionClientFactory newFactory(
      @Nonnull WorkflowServiceStubs service,
      @Nonnull String namespace,
      @Nonnull String identity,
      @Nonnull DataConverter dataConverter) {
    return new ManualActivityCompletionClientFactoryImpl(
        service, namespace, identity, dataConverter);
  }

  ManualActivityCompletionClient getClient(@Nonnull byte[] taskToken, @Nonnull Scope metricsScope);

  ManualActivityCompletionClient getClient(
      @Nonnull byte[] taskToken,
      @Nonnull Scope metricsScope,
      @Nullable ActivitySerializationContext activitySerializationContext);

  ManualActivityCompletionClient getClient(
      @Nonnull WorkflowExecution execution,
      @Nonnull String activityId,
      @Nonnull Scope metricsScope);

  ManualActivityCompletionClient getClient(
      @Nonnull WorkflowExecution execution,
      @Nonnull String activityId,
      @Nonnull Scope metricsScope,
      @Nullable ActivitySerializationContext activitySerializationContext);
}
