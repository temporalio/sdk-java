package io.temporal.common.interceptors;

import com.uber.m3.tally.Scope;
import io.temporal.common.SearchAttributeUpdate;
import io.temporal.workflow.Functions.Func;
import io.temporal.workflow.MutableSideEffectOptions;
import io.temporal.workflow.Promise;
import io.temporal.workflow.SideEffectOptions;
import io.temporal.workflow.TimerOptions;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Supplier;

/** Convenience base class for WorkflowOutboundCallsInterceptor implementations. */
public class WorkflowOutboundCallsInterceptorBase implements WorkflowOutboundCallsInterceptor {

  private final WorkflowOutboundCallsInterceptor next;

  public WorkflowOutboundCallsInterceptorBase(WorkflowOutboundCallsInterceptor next) {
    this.next = next;
  }

  @Override
  public <R> ActivityOutput<R> executeActivity(ActivityInput<R> input) {
    return next.executeActivity(input);
  }

  @Override
  public <R> LocalActivityOutput<R> executeLocalActivity(LocalActivityInput<R> input) {
    return next.executeLocalActivity(input);
  }

  @Override
  public <R> ChildWorkflowOutput<R> executeChildWorkflow(ChildWorkflowInput<R> input) {
    return next.executeChildWorkflow(input);
  }

  @Override
  public <R> ExecuteNexusOperationOutput<R> executeNexusOperation(
      ExecuteNexusOperationInput<R> input) {
    return next.executeNexusOperation(input);
  }

  @Override
  public Random newRandom() {
    return next.newRandom();
  }

  @Override
  public SignalExternalOutput signalExternalWorkflow(SignalExternalInput input) {
    return next.signalExternalWorkflow(input);
  }

  @Override
  public CancelWorkflowOutput cancelWorkflow(CancelWorkflowInput input) {
    return next.cancelWorkflow(input);
  }

  @Override
  public void sleep(Duration duration) {
    next.sleep(duration);
  }

  @Override
  public boolean await(Duration timeout, String reason, Supplier<Boolean> unblockCondition) {
    return next.await(timeout, reason, unblockCondition);
  }

  @Override
  public void await(String reason, Supplier<Boolean> unblockCondition) {
    next.await(reason, unblockCondition);
  }

  @Override
  public Promise<Void> newTimer(Duration duration) {
    return next.newTimer(duration);
  }

  @Override
  public Promise<Void> newTimer(Duration duration, TimerOptions options) {
    return next.newTimer(duration, options);
  }

  @Override
  public <R> R sideEffect(Class<R> resultClass, Type resultType, Func<R> func) {
    return next.sideEffect(resultClass, resultType, func);
  }

  @Override
  public <R> R sideEffect(
      Class<R> resultClass, Type resultType, Func<R> func, SideEffectOptions options) {
    return next.sideEffect(resultClass, resultType, func, options);
  }

  @Override
  public <R> R mutableSideEffect(
      String id, Class<R> resultClass, Type resultType, BiPredicate<R, R> updated, Func<R> func) {
    return next.mutableSideEffect(id, resultClass, resultType, updated, func);
  }

  @Override
  public <R> R mutableSideEffect(
      String id,
      Class<R> resultClass,
      Type resultType,
      BiPredicate<R, R> updated,
      Func<R> func,
      MutableSideEffectOptions options) {
    return next.mutableSideEffect(id, resultClass, resultType, updated, func, options);
  }

  @Override
  public int getVersion(String changeId, int minSupported, int maxSupported) {
    return next.getVersion(changeId, minSupported, maxSupported);
  }

  @Override
  public void continueAsNew(ContinueAsNewInput input) {
    next.continueAsNew(input);
  }

  @Override
  public void registerQuery(RegisterQueryInput input) {
    next.registerQuery(input);
  }

  @Override
  public void registerSignalHandlers(RegisterSignalHandlersInput input) {
    next.registerSignalHandlers(input);
  }

  @Override
  public void registerUpdateHandlers(RegisterUpdateHandlersInput input) {
    next.registerUpdateHandlers(input);
  }

  @Override
  public void registerDynamicSignalHandler(RegisterDynamicSignalHandlerInput input) {
    next.registerDynamicSignalHandler(input);
  }

  @Override
  public void registerDynamicQueryHandler(RegisterDynamicQueryHandlerInput input) {
    next.registerDynamicQueryHandler(input);
  }

  @Override
  public void registerDynamicUpdateHandler(RegisterDynamicUpdateHandlerInput input) {
    next.registerDynamicUpdateHandler(input);
  }

  @Override
  public UUID randomUUID() {
    return next.randomUUID();
  }

  @Override
  public void upsertSearchAttributes(Map<String, ?> searchAttributes) {
    next.upsertSearchAttributes(searchAttributes);
  }

  @Override
  public void upsertTypedSearchAttributes(SearchAttributeUpdate<?>... searchAttributeUpdates) {
    next.upsertTypedSearchAttributes(searchAttributeUpdates);
  }

  @Override
  public void upsertMemo(Map<String, Object> memo) {
    next.upsertMemo(memo);
  }

  @Override
  public Scope getMetricsScope() {
    return next.getMetricsScope();
  }

  @Override
  public Object newChildThread(Runnable runnable, boolean detached, String name) {
    return next.newChildThread(runnable, detached, name);
  }

  @Override
  public long currentTimeMillis() {
    return next.currentTimeMillis();
  }
}
