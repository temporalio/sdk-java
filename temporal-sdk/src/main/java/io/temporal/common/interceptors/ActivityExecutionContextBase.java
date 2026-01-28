package io.temporal.common.interceptors;

import com.uber.m3.tally.Scope;
import io.temporal.activity.ActivityExecutionContext;
import io.temporal.activity.ActivityInfo;
import io.temporal.activity.ManualActivityCompletionClient;
import io.temporal.client.ActivityCompletionException;
import io.temporal.client.WorkflowClient;
import java.lang.reflect.Type;
import java.util.Optional;

/** Convenience class for implementing ActivityInterceptors. */
public class ActivityExecutionContextBase implements ActivityExecutionContext {
  private final ActivityExecutionContext next;

  public ActivityExecutionContextBase(ActivityExecutionContext next) {
    this.next = next;
  }

  @Override
  public ActivityInfo getInfo() {
    return next.getInfo();
  }

  @Override
  public <V> void heartbeat(V details) throws ActivityCompletionException {
    next.heartbeat(details);
  }

  @Override
  public <V> Optional<V> getHeartbeatDetails(Class<V> detailsClass) {
    return next.getHeartbeatDetails(detailsClass);
  }

  @Override
  public <V> Optional<V> getHeartbeatDetails(Class<V> detailsClass, Type detailsGenericType) {
    return next.getHeartbeatDetails(detailsClass, detailsGenericType);
  }

  @Override
  public <V> Optional<V> getLastHeartbeatDetails(Class<V> detailsClass) {
    return next.getLastHeartbeatDetails(detailsClass);
  }

  @Override
  public <V> Optional<V> getLastHeartbeatDetails(Class<V> detailsClass, Type detailsGenericType) {
    return next.getLastHeartbeatDetails(detailsClass, detailsGenericType);
  }

  @Override
  public byte[] getTaskToken() {
    return next.getTaskToken();
  }

  @Override
  public void doNotCompleteOnReturn() {
    next.doNotCompleteOnReturn();
  }

  @Override
  public boolean isDoNotCompleteOnReturn() {
    return next.isDoNotCompleteOnReturn();
  }

  @Override
  public boolean isUseLocalManualCompletion() {
    return next.isUseLocalManualCompletion();
  }

  @Override
  public ManualActivityCompletionClient useLocalManualCompletion() {
    return next.useLocalManualCompletion();
  }

  @Override
  public Scope getMetricsScope() {
    return next.getMetricsScope();
  }

  @Override
  public WorkflowClient getWorkflowClient() {
    return next.getWorkflowClient();
  }

  @Override
  public Object getInstance() {
    return next.getInstance();
  }
}
