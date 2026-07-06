package io.temporal.opentelemetry;

import com.uber.m3.tally.Scope;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Reports buffered Tally metrics without closing the scope. */
public final class TallyScopeFlushHook implements Runnable {
  private static final Logger log = LoggerFactory.getLogger(TallyScopeFlushHook.class);

  private final Scope scope;

  public TallyScopeFlushHook(Scope scope) {
    this.scope = Objects.requireNonNull(scope, "scope");
  }

  @Override
  public void run() {
    try {
      Method reportLoopIteration = scope.getClass().getDeclaredMethod("reportLoopIteration");
      reportLoopIteration.setAccessible(true);
      reportLoopIteration.invoke(scope);
    } catch (NoSuchMethodException
        | IllegalAccessException
        | InvocationTargetException
        | RuntimeException e) {
      log.warn("Tally scope flush failed scope={}", scope.getClass().getName(), e);
    }
  }
}
