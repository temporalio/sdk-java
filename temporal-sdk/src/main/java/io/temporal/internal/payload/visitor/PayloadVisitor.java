package io.temporal.internal.payload.visitor;

import io.temporal.api.common.v1.Payload;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Callback completing with the list that replaces {@code payloads}; complete with the same list to
 * leave them unchanged. Asynchronous so I/O-backed implementations (e.g. external storage) compose
 * without blocking a thread per call; a synchronous one returns {@link
 * CompletableFuture#completedFuture}.
 *
 * <p>For a single-payload field the visitor must complete with exactly one payload. With
 * concurrency greater than one, several visits may be in flight at once, so implementations must be
 * thread-safe.
 *
 * @param <C> type of the contextual value supplied to each visit
 */
@FunctionalInterface
interface PayloadVisitor<C> {
  CompletableFuture<List<Payload>> visit(C context, List<Payload> payloads);
}
