package io.temporal.internal;

import io.temporal.conf.EnvironmentVariableNames;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;

/**
 * Provides an access to information about a thread type the current code executes in to perform
 * different type of access checks inside Temporal library code.
 *
 * <p>Note: This class is a singleton and is not intended for an extension.
 *
 * <p>Note: This class shouldn't be accessed in any way by the application code.
 */
public abstract class WorkflowThreadMarker {
  protected static final ThreadLocal<Boolean> isWorkflowThreadThreadLocal =
      ThreadLocal.withInitial(() -> false);

  private static final boolean enableEnforcements;

  static {
    String envValue =
        System.getenv(EnvironmentVariableNames.DISABLE_NON_WORKFLOW_CODE_ENFORCEMENTS);
    enableEnforcements = envValue == null || "false".equalsIgnoreCase(envValue);
  }

  /**
   * @return true if the current thread is workflow thread
   */
  public static boolean isWorkflowThread() {
    return isWorkflowThreadThreadLocal.get();
  }

  /**
   * Throws {@link IllegalStateException} if it's called from workflow thread.
   *
   * @see io.temporal.conf.EnvironmentVariableNames#DISABLE_NON_WORKFLOW_CODE_ENFORCEMENTS
   */
  public static void enforceNonWorkflowThread() {
    if (enableEnforcements && isWorkflowThread()) {
      throw new IllegalStateException("Cannot be called from workflow thread.");
    }
  }

  /**
   * Create a proxy that checks all methods executions if they are done from a workflow thread and
   * makes them throw an IllegalStateException if they are indeed triggered from workflow code
   *
   * @param instance an instance to wrap
   * @param iface an interface the {@code instance} implements and that proxy should implement and
   *     intercept
   * @return a proxy that makes sure that it's methods can't be called from workflow thread
   */
  @SuppressWarnings("unchecked")
  public static <T> T protectFromWorkflowThread(T instance, Class<T> iface) {
    return (T)
        Proxy.newProxyInstance(
            iface.getClassLoader(),
            new Class<?>[] {iface},
            (proxy, method, args) -> {
              enforceNonWorkflowThread();
              try {
                return method.invoke(instance, args);
              } catch (InvocationTargetException e) {
                throw e.getCause();
              }
            });
  }
}
