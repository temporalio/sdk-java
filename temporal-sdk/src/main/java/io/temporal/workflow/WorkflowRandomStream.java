package io.temporal.workflow;

import io.temporal.common.Experimental;
import io.temporal.workflow.unsafe.WorkflowUnsafe;

/**
 * A named deterministic pseudorandom stream for Workflow code.
 *
 * <p>Repeated calls to {@link Workflow#getRandomStream(String)} with the same name return the same
 * logical stream at its current position. Different names do not affect each other's sequences.
 *
 * <p>Each draw advances Workflow state without recording an Event in Workflow History. Replay must
 * make the same draws in the same order. Do not draw in read-only code; shared code can check
 * {@link WorkflowUnsafe#isReadOnly()}.
 *
 * <p>Use a stable package-style name. Stream names are retained for the life of the Workflow Run.
 */
@Experimental
public interface WorkflowRandomStream {

  /** Fills {@code bytes} with the next bytes from this stream. */
  void nextBytes(byte[] bytes);

  /** Returns the next signed 64-bit value from this stream in big-endian byte order. */
  long nextLong();
}
