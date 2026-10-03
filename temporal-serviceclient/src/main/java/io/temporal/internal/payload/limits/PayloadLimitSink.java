package io.temporal.internal.payload.limits;

/**
 * Receives one callback per validated payload field, with the field's size as the server measures
 * it. Implementors decide how to handle warnings and errors.
 *
 * <p>The generated traversal ({@code GeneratedPayloadLimitValidator}) calls {@link #enter}/{@link
 * #exit} around each nested message so the sink can track a field's location for {@link #check}.
 */
interface PayloadLimitSink {
  /**
   * Called for each validated payload field.
   *
   * @param fieldName the leaf field's proto name
   * @param limitClass which limit the field is subject to
   * @param size the field's size in bytes for the given class
   * @param enforceError when {@code false}, the field may warn but must never produce an
   *     error-level violation
   */
  void check(String fieldName, LimitClass limitClass, long size, boolean enforceError);

  /** Enter a singular nested-message field with proto name {@code name}. */
  void enter(String name);

  /** Enter element {@code index} of a repeated nested-message field {@code name}. */
  void enter(String name, int index);

  /** Enter the entry under {@code key} of a map-valued nested-message field {@code name}. */
  void enter(String name, String key);

  /** Leave the most recently entered nested field. */
  void exit();
}
