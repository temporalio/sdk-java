package io.temporal.internal.payload.limits;

/** A payload field whose size exceeded one of its configured thresholds (warning or error). */
final class PayloadLimitViolation {
  private final String path;
  private final LimitClass limitClass;
  private final LimitSeverity severity;
  private final long size;
  private final long limit;

  PayloadLimitViolation(
      String path, LimitClass limitClass, LimitSeverity severity, long size, long limit) {
    this.path = path;
    this.limitClass = limitClass;
    this.severity = severity;
    this.size = size;
    this.limit = limit;
  }

  /**
   * Path of proto field names from the root message (e.g. {@code
   * commands[2].schedule_activity_task_command_attributes.input}).
   */
  String getPath() {
    return path;
  }

  LimitClass getLimitClass() {
    return limitClass;
  }

  LimitSeverity getSeverity() {
    return severity;
  }

  /** The field's measured size in bytes. */
  long getSize() {
    return size;
  }

  /**
   * The threshold that was exceeded (warning threshold for warnings, error threshold for errors).
   */
  long getLimit() {
    return limit;
  }

  /** The user-facing {@code [TMPRL1103]} message. */
  String getMessage() {
    String limitClassName = limitClass == LimitClass.BLOB ? "payloads" : "memo";
    String limitKind = severity == LimitSeverity.WARNING ? "warning" : "error";
    return "[TMPRL1103] Attempted to upload "
        + limitClassName
        + " with size that exceeded the "
        + limitKind
        + " limit.";
  }

  @Override
  public String toString() {
    return getMessage();
  }
}
