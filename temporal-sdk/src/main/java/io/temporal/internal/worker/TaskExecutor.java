package io.temporal.internal.worker;

import java.util.concurrent.RejectedExecutionException;
import javax.annotation.Nonnull;

interface TaskExecutor<T> {
  /**
   * @param task to be processed
   * @throws RejectedExecutionException at discretion of {@code RejectedExecutionHandler}, if the
   *     task cannot be accepted for execution
   * @throws NullPointerException if {@code command} is null
   */
  void process(@Nonnull T task);
}
