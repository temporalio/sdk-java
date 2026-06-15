package io.temporal.internal.activity;

import com.uber.m3.tally.Scope;
import io.temporal.client.WorkflowClient;
import io.temporal.common.converter.DataConverter;
import io.temporal.internal.client.external.ManualActivityCompletionClientFactory;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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
  private final ConcurrentMap<ByteBuffer, ActivityExecutionContextImpl> activeContexts =
      new ConcurrentHashMap<>();

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
    ByteBuffer taskToken = taskTokenKey(info.getTaskToken());
    ActivityExecutionContextImpl context =
        new ActivityExecutionContextImpl(
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
            defaultHeartbeatThrottleInterval,
            () -> activeContexts.remove(taskToken));
    activeContexts.put(taskToken, context);
    return context;
  }

  @Override
  public boolean requestCancel(byte[] taskToken) {
    ActivityExecutionContextImpl context = activeContexts.get(taskTokenKey(taskToken));
    if (context == null) {
      return false;
    }
    context.cancelFromWorkerCommand();
    return true;
  }

  private static ByteBuffer taskTokenKey(byte[] taskToken) {
    return ByteBuffer.wrap(Arrays.copyOf(taskToken, taskToken.length)).asReadOnlyBuffer();
  }
}
