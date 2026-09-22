package io.temporal.springai.util;

import io.temporal.internal.sync.ActivityInvocationHandler;
import io.temporal.internal.sync.LocalActivityInvocationHandler;
import io.temporal.internal.sync.NexusServiceInvocationHandler;
import java.lang.reflect.Proxy;

/**
 * Utility class for detecting Temporal stub types.
 *
 * <p>Temporal creates dynamic proxies for various stub types (activities, local activities, child
 * workflows, Nexus services). This utility provides methods to detect what type of stub an object
 * is, which is useful for determining how to handle tool calls.
 *
 * <p>This class uses direct {@code instanceof} checks against the SDK's internal invocation handler
 * classes. Since the {@code temporal-spring-ai} module lives in the SDK repo, this coupling is
 * intentional and will be caught by compilation if the handler classes are renamed or moved.
 */
public final class TemporalStubUtil {

  private TemporalStubUtil() {}

  /**
   * Checks if the given object is an activity stub created by {@code Workflow.newActivityStub()}.
   *
   * @param object the object to check
   * @return true if the object is an activity stub (but not a local activity stub)
   */
  public static boolean isActivityStub(Object object) {
    if (object == null || !Proxy.isProxyClass(object.getClass())) {
      return false;
    }
    var handler = Proxy.getInvocationHandler(object);
    return handler instanceof ActivityInvocationHandler;
  }

  /**
   * Checks if the given object is a local activity stub created by {@code
   * Workflow.newLocalActivityStub()}.
   *
   * @param object the object to check
   * @return true if the object is a local activity stub
   */
  public static boolean isLocalActivityStub(Object object) {
    if (object == null || !Proxy.isProxyClass(object.getClass())) {
      return false;
    }
    var handler = Proxy.getInvocationHandler(object);
    return handler instanceof LocalActivityInvocationHandler;
  }

  /**
   * Checks if the given object is a child workflow stub created by {@code
   * Workflow.newChildWorkflowStub()}.
   *
   * <p>Note: {@code ChildWorkflowInvocationHandler} is package-private in the SDK, so we check via
   * the class name. This is safe because the module lives in the SDK repo — any rename would break
   * compilation of this module's tests.
   *
   * @param object the object to check
   * @return true if the object is a child workflow stub
   */
  public static boolean isChildWorkflowStub(Object object) {
    if (object == null || !Proxy.isProxyClass(object.getClass())) {
      return false;
    }
    var handler = Proxy.getInvocationHandler(object);
    // ChildWorkflowInvocationHandler is package-private, so we use class name check.
    // This is the only handler where instanceof is not possible.
    return handler.getClass().getName().endsWith("ChildWorkflowInvocationHandler");
  }

  /**
   * Checks if the given object is a Nexus service stub created by {@code
   * Workflow.newNexusServiceStub()}.
   *
   * @param object the object to check
   * @return true if the object is a Nexus service stub
   */
  public static boolean isNexusServiceStub(Object object) {
    if (object == null || !Proxy.isProxyClass(object.getClass())) {
      return false;
    }
    var handler = Proxy.getInvocationHandler(object);
    return handler instanceof NexusServiceInvocationHandler;
  }
}
