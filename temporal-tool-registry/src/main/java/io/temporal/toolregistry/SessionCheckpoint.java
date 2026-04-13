package io.temporal.toolregistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Data serialized to the Temporal heartbeat on each turn of {@link AgenticSession#runToolLoop}.
 *
 * <p>Package-private — callers interact only with {@link AgenticSession}.
 */
class SessionCheckpoint {

  /** Checkpoint schema version. Absent (0) in pre-versioned checkpoints. */
  public int version = 1;

  public List<Map<String, Object>> messages = new ArrayList<>();
  public List<Map<String, Object>> issues = new ArrayList<>();

  /** No-arg constructor required for Jackson deserialization. */
  SessionCheckpoint() {}

  SessionCheckpoint(List<Map<String, Object>> messages, List<Map<String, Object>> issues) {
    this.messages = new ArrayList<>(messages);
    this.issues = new ArrayList<>(issues);
  }
}
