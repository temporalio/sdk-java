package io.temporal.worker;

/**
 * This exception is thrown when worker has
 *
 * <ul>
 *   <li>an activity instance for the activity type
 *   <li>a workflow type or factory for the workflow type
 * </ul>
 *
 * already registered.
 */
public class TypeAlreadyRegisteredException extends IllegalStateException {
  private final String registeredTypeName;

  public TypeAlreadyRegisteredException(String registeredTypeName, String message) {
    super(message);
    this.registeredTypeName = registeredTypeName;
  }

  /** Workflow or Activity type that is already registered */
  public String getRegisteredTypeName() {
    return registeredTypeName;
  }
}
