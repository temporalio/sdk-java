package io.temporal.springai.util;

import java.lang.reflect.Proxy;

/**
 * Utility class for detecting and working with Temporal stub types.
 *
 * <p>Temporal creates dynamic proxies for various stub types (activities, local activities, child
 * workflows, Nexus services). This utility provides methods to detect what type of stub an object
 * is, which is useful for determining how to handle tool calls.
 */
public final class TemporalStubUtil {

  private TemporalStubUtil() {
    // Utility class
  }

  /**
   * Checks if the given object is an activity stub created by {@code Workflow.newActivityStub()}.
   *
   * @param object the object to check
   * @return true if the object is an activity stub
   */
  public static boolean isActivityStub(Object object) {
    return object != null
        && Proxy.isProxyClass(object.getClass())
        && Proxy.getInvocationHandler(object)
            .getClass()
            .getName()
            .contains("ActivityInvocationHandler")
        && !isLocalActivityStub(object);
  }

  /**
   * Checks if the given object is a local activity stub created by {@code
   * Workflow.newLocalActivityStub()}.
   *
   * @param object the object to check
   * @return true if the object is a local activity stub
   */
  public static boolean isLocalActivityStub(Object object) {
    return object != null
        && Proxy.isProxyClass(object.getClass())
        && Proxy.getInvocationHandler(object)
            .getClass()
            .getName()
            .contains("LocalActivityInvocationHandler");
  }

  /**
   * Checks if the given object is a child workflow stub created by {@code
   * Workflow.newChildWorkflowStub()}.
   *
   * @param object the object to check
   * @return true if the object is a child workflow stub
   */
  public static boolean isChildWorkflowStub(Object object) {
    return object != null
        && Proxy.isProxyClass(object.getClass())
        && Proxy.getInvocationHandler(object)
            .getClass()
            .getName()
            .contains("ChildWorkflowInvocationHandler");
  }

  /**
   * Checks if the given object is a Nexus service stub created by {@code
   * Workflow.newNexusServiceStub()}.
   *
   * @param object the object to check
   * @return true if the object is a Nexus service stub
   */
  public static boolean isNexusServiceStub(Object object) {
    return object != null
        && Proxy.isProxyClass(object.getClass())
        && Proxy.getInvocationHandler(object)
            .getClass()
            .getName()
            .contains("NexusServiceInvocationHandler");
  }
}
