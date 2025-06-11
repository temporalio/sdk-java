package io.temporal.internal.nexus;

/**
 * Thread local store of the context object passed to a nexus operation implementation. Not to be
 * used directly.
 */
public final class CurrentNexusOperationContext {
  private static final ThreadLocal<InternalNexusOperationContext> CURRENT = new ThreadLocal<>();

  public static boolean isNexusContext() {
    return CURRENT.get() != null;
  }

  public static InternalNexusOperationContext get() {
    InternalNexusOperationContext result = CURRENT.get();
    if (result == null) {
      throw new IllegalStateException(
          "NexusOperationContext can be used only inside of nexus operation handler "
              + "implementation methods and in the same thread that invoked the operation.");
    }
    return CURRENT.get();
  }

  public static void set(InternalNexusOperationContext context) {
    if (context == null) {
      throw new IllegalArgumentException("null context");
    }
    if (CURRENT.get() != null) {
      throw new IllegalStateException("current already set");
    }
    CURRENT.set(context);
  }

  public static void unset() {
    CURRENT.set(null);
  }

  private CurrentNexusOperationContext() {}
}
