package io.temporal.internal.worker;

import javax.annotation.Nullable;

/** Interface for tasks that can provide scaling feedback from the server. */
public interface ScalingTask {
  /**
   * Represents a scaling decision made by the task. It contains a suggestion for the delta in the
   * number of poll requests.
   */
  class ScalingDecision {
    private final int pollRequestDeltaSuggestion;

    public ScalingDecision(int pollRequestDeltaSuggestion) {
      this.pollRequestDeltaSuggestion = pollRequestDeltaSuggestion;
    }

    public int getPollRequestDeltaSuggestion() {
      return pollRequestDeltaSuggestion;
    }
  }

  /**
   * Returns a scaling decision from the task. The decision may be null if no scaling action is
   * needed or not supported.
   *
   * @return a ScalingDecision object containing the scaling suggestion, or null if no action is
   *     needed not supported.
   */
  @Nullable
  ScalingDecision getScalingDecision();
}
