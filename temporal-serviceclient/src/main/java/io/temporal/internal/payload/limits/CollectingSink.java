package io.temporal.internal.payload.limits;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@link PayloadLimitSink} that collects violations without logging or policy decisions.
 *
 * <p>Each checked field is sorted into {@link #getWarnings()} or {@link #getErrors()}: a field over
 * its error threshold (when {@code enforceError} and an error threshold are set) is an error;
 * otherwise a field over its warning threshold is a warning.
 */
final class CollectingSink implements PayloadLimitSink {
  private final PayloadLimits limits;
  private final PayloadPath path = new PayloadPath();
  private final List<PayloadLimitViolation> warnings = new ArrayList<>();
  private final List<PayloadLimitViolation> errors = new ArrayList<>();

  CollectingSink(PayloadLimits limits) {
    this.limits = limits;
  }

  @Override
  public void check(String fieldName, LimitClass limitClass, long size, boolean enforceError) {
    long error = limits.error(limitClass);
    long warn = limits.warn(limitClass);
    if (enforceError && error > 0 && size > error) {
      errors.add(
          new PayloadLimitViolation(
              path.leaf(fieldName), limitClass, LimitSeverity.ERROR, size, error));
    } else if (warn > 0 && size > warn) {
      warnings.add(
          new PayloadLimitViolation(
              path.leaf(fieldName), limitClass, LimitSeverity.WARNING, size, warn));
    }
  }

  @Override
  public void enter(String name) {
    path.push(name);
  }

  @Override
  public void enter(String name, int index) {
    path.push(name, index);
  }

  @Override
  public void enter(String name, String key) {
    path.push(name, key);
  }

  @Override
  public void exit() {
    path.pop();
  }

  List<PayloadLimitViolation> getWarnings() {
    return warnings;
  }

  List<PayloadLimitViolation> getErrors() {
    return errors;
  }
}
