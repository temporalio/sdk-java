package io.temporal.internal.payload.visitor;

import io.temporal.api.common.v1.Payload;
import java.util.List;

/**
 * Callback for a sequence of payloads found in a proto message. The returned list replaces those
 * payloads; return the same list to leave them unchanged.
 *
 * <p>When the visited field holds a single payload the list has one element and the visitor must
 * return exactly one payload. With a concurrency limit greater than one, visits may run on multiple
 * threads, so implementations must be thread-safe.
 *
 * @param <C> type of the contextual value supplied to each visit
 */
@FunctionalInterface
interface PayloadVisitor<C> {
  /**
   * Visits a sequence of payloads and returns their replacements.
   *
   * @param context the location of these payloads and the contextual value in scope
   * @param payloads the payloads found at this location
   * @return the replacement payloads
   */
  List<Payload> visit(PayloadVisitorContext<C> context, List<Payload> payloads);
}
