package io.temporal.toolregistry;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/** The result of one turn of the LLM conversation loop. */
public final class TurnResult {

  private final List<Map<String, Object>> newMessages;
  private final boolean done;

  public TurnResult(List<Map<String, Object>> newMessages, boolean done) {
    this.newMessages = Collections.unmodifiableList(newMessages);
    this.done = done;
  }

  /**
   * The new messages produced during this turn (assistant response and any tool results). Append
   * these to the conversation history before calling the next turn.
   */
  public List<Map<String, Object>> getNewMessages() {
    return newMessages;
  }

  /** Returns {@code true} when the loop should stop. */
  public boolean isDone() {
    return done;
  }
}
