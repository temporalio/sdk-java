package io.temporal.internal.activity;

import com.uber.m3.tally.Scope;
import io.temporal.client.WorkflowClient;
import io.temporal.common.converter.DataConverter;
import io.temporal.internal.client.external.ManualActivityCompletionClientFactory;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;

public class ActivityExecutionContextFactoryImpl implements ActivityExecutionContextFactory {
  private final WorkflowClient client;
  private final String identity;
  private final String namespace;
  private final Duration maxHeartbeatThrottleInterval;
  private final Duration defaultHeartbeatThrottleInterval;
  private final DataConverter dataConverter;
  private final ScheduledExecutorService heartbeatExecutor;
  private final ManualActivityCompletionClientFactory manualCompletionClientFactory;

  public ActivityExecutionContextFactoryImpl(
      WorkflowClient client,
      String identity,
      String namespace,
      Duration maxHeartbeatThrottleInterval,
      Duration defaultHeartbeatThrottleInterval,
      DataConverter dataConverter,
      ScheduledExecutorService heartbeatExecutor) {
    this.client = Objects.requireNonNull(client);
    this.identity = identity;
    this.namespace = Objects.requireNonNull(namespace);
    this.maxHeartbeatThrottleInterval = Objects.requireNonNull(maxHeartbeatThrottleInterval);
    this.defaultHeartbeatThrottleInterval =
        Objects.requireNonNull(defaultHeartbeatThrottleInterval);
    this.dataConverter = Objects.requireNonNull(dataConverter);
    this.heartbeatExecutor = Objects.requireNonNull(heartbeatExecutor);
    this.manualCompletionClientFactory =
        ManualActivityCompletionClientFactory.newFactory(
            client.getWorkflowServiceStubs(), namespace, identity, dataConverter);
  }

  @Override
  public InternalActivityExecutionContext createContext(
      ActivityInfoInternal info, Object activity, Scope metricsScope) {
    return new ActivityExecutionContextImpl(
        client,
        namespace,
        activity,
        info,
        dataConverter,
        heartbeatExecutor,
        manualCompletionClientFactory,
        info.getCompletionHandle(),
        metricsScope,
        identity,
        maxHeartbeatThrottleInterval,
        defaultHeartbeatThrottleInterval);
  }
}
