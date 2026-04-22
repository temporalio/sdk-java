package io.temporal.toolregistry;

/**
 * Functional callback passed to {@link AgenticSession#runWithSession}. Receives a fully initialized
 * (and optionally checkpoint-restored) session.
 */
@FunctionalInterface
public interface SessionFn {
  void run(AgenticSession session) throws Exception;
}
