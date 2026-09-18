package io.temporal.internal.payload.limits;

import java.util.ArrayList;
import java.util.List;

/**
 * Path of proto field names to the field being validated; proto names keep it language-agnostic. A
 * helper sinks embed to track location across {@link PayloadLimitSink#enter}/{@link
 * PayloadLimitSink#exit}.
 *
 * <p>Segments are kept unrendered so that entering and leaving a message costs no allocation; the
 * path string is built only by {@link #leaf}, which a sink calls only when it has a violation to
 * report. The traversal runs on every outbound request, while violations are rare.
 */
final class PayloadPath {
  private static final int NO_INDEX = -1;

  private final List<String> names = new ArrayList<>();
  private final List<String> keys = new ArrayList<>();
  private final List<Integer> indexes = new ArrayList<>();

  void push(String name) {
    push(name, null, NO_INDEX);
  }

  void push(String name, int index) {
    push(name, null, index);
  }

  void push(String name, String key) {
    push(name, key, NO_INDEX);
  }

  private void push(String name, String key, int index) {
    names.add(name);
    keys.add(key);
    indexes.add(index);
  }

  void pop() {
    int last = names.size() - 1;
    names.remove(last);
    keys.remove(last);
    indexes.remove(last);
  }

  /** The full dotted path to a leaf field with proto name {@code fieldName}. */
  String leaf(String fieldName) {
    if (names.isEmpty()) {
      return fieldName;
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < names.size(); i++) {
      sb.append(names.get(i));
      String key = keys.get(i);
      if (key != null) {
        sb.append('[').append(key).append(']');
      } else if (indexes.get(i) != NO_INDEX) {
        sb.append('[').append(indexes.get(i)).append(']');
      }
      sb.append('.');
    }
    return sb.append(fieldName).toString();
  }
}
