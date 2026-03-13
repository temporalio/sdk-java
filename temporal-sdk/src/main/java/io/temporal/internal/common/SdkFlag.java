package io.temporal.internal.common;

/**
 * SdkFlag represents a flag used to help version the sdk internally to make breaking changes in
 * workflow logic.
 */
public enum SdkFlag {
  UNSET(0),
  /*
   * Changes behavior of GetVersion to not yield if no previous call existed in history.
   */
  SKIP_YIELD_ON_DEFAULT_VERSION(1),
  /*
   * Changes behavior of GetVersion to never yield.
   */
  SKIP_YIELD_ON_VERSION(2),
  /*
   * Changes behavior of CancellationScope to cancel children in a deterministic order.
   */
  DETERMINISTIC_CANCELLATION_SCOPE_ORDER(3),
  /*
   * Changes behavior of Workflow.await(duration, condition) to cancel the timer if the
   * condition is resolved before the timeout.
   */
  CANCEL_AWAIT_TIMER_ON_CONDITION(4),
  UNKNOWN(Integer.MAX_VALUE);

  private final int value;

  SdkFlag(int value) {
    this.value = value;
  }

  public boolean compare(int i) {
    return value == i;
  }

  public static SdkFlag getValue(int id) {
    SdkFlag[] as = SdkFlag.values();
    for (int i = 0; i < as.length; i++) {
      if (as[i].compare(id)) return as[i];
    }
    return SdkFlag.UNKNOWN;
  }

  public int getValue() {
    return value;
  }
}
