package io.temporal.internal.replay;

import io.temporal.api.failure.v1.Failure;
import io.temporal.common.VersioningBehavior;
import io.temporal.common.context.ContextPropagator;
import io.temporal.internal.sync.SignalHandlerInfo;
import io.temporal.internal.sync.UpdateHandlerInfo;
import io.temporal.worker.WorkflowImplementationOptions;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Core top level workflow context */
public interface WorkflowContext {
  ReplayWorkflowContext getReplayContext();

  /**
   * Convert exception to the serialized Failure that can be reported to the server.<br>
   * This method is needed when framework code needs to serialize a {@link
   * io.temporal.failure.TemporalFailure} instance with details object produced by the application
   * code.<br>
   * The framework code is not aware of DataConverter so this is working around this layering.
   *
   * @param exception throwable to convert
   * @return Serialized failure
   */
  Failure mapWorkflowExceptionToFailure(Throwable exception);

  @Nonnull
  WorkflowImplementationOptions getWorkflowImplementationOptions();

  /**
   * @return Deserialized completion result of the last cron workflow run
   */
  @Nullable
  <R> R getLastCompletionResult(Class<R> resultClass, Type resultType);

  /**
   * @return the list of configured context propagators
   */
  List<ContextPropagator> getContextPropagators();

  /**
   * Returns all current contexts being propagated by a {@link
   * io.temporal.common.context.ContextPropagator}. The key is the {@link
   * ContextPropagator#getName()} and the value is the object returned by {@link
   * ContextPropagator#getCurrentContext()}
   */
  Map<String, Object> getPropagatedContexts();

  Map<Long, SignalHandlerInfo> getRunningSignalHandlers();

  Map<String, UpdateHandlerInfo> getRunningUpdateHandlers();

  VersioningBehavior getVersioningBehavior();
}
