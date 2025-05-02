package io.temporal.internal.activity;

import com.uber.m3.tally.Scope;
import io.temporal.activity.ActivityInfo;
import io.temporal.activity.ManualActivityCompletionClient;
import io.temporal.client.ActivityCompletionException;
import io.temporal.client.WorkflowClient;
import java.lang.reflect.Type;
import java.util.Optional;

class LocalActivityExecutionContextImpl implements InternalActivityExecutionContext {
  private final WorkflowClient client;
  private final Object activity;
  private final ActivityInfo info;
  private final Scope metricsScope;

  LocalActivityExecutionContextImpl(
      WorkflowClient client, Object activity, ActivityInfo info, Scope metricsScope) {
    this.client = client;
    this.activity = activity;
    this.info = info;
    this.metricsScope = metricsScope;
  }

  @Override
  public ActivityInfo getInfo() {
    return info;
  }

  @Override
  public <V> void heartbeat(V details) throws ActivityCompletionException {
    // Ignored
  }

  @Override
  public <V> Optional<V> getHeartbeatDetails(Class<V> detailsClass) {
    return Optional.empty();
  }

  @Override
  public <V> Optional<V> getHeartbeatDetails(Class<V> detailsClass, Type detailsGenericType) {
    return Optional.empty();
  }

  @Override
  public byte[] getTaskToken() {
    throw new UnsupportedOperationException("getTaskToken is not supported for local activities");
  }

  @Override
  public void doNotCompleteOnReturn() {
    throw new UnsupportedOperationException(
        "doNotCompleteOnReturn is not supported for local activities");
  }

  @Override
  public boolean isDoNotCompleteOnReturn() {
    return false;
  }

  @Override
  public boolean isUseLocalManualCompletion() {
    return false;
  }

  @Override
  public ManualActivityCompletionClient useLocalManualCompletion() {
    throw new UnsupportedOperationException(
        "getManualCompletionClient is not supported for local activities");
  }

  @Override
  public Scope getMetricsScope() {
    return metricsScope;
  }

  @Override
  public Object getLastHeartbeatValue() {
    return null;
  }

  @Override
  public WorkflowClient getWorkflowClient() {
    return client;
  }

  @Override
  public Object getInstance() {
    return activity;
  }
}
