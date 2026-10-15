package io.temporal.common;

/**
 * Supports isolated in-process value transfer without a serialization round trip.
 *
 * <p>Copies must not share mutable reachable state with the original. Deeply immutable values may
 * return {@code this}. A copy must be non-null and compatible with the original's runtime type.
 *
 * <p>Copying must be side-effect-free, safe for concurrent calls, and deterministically equivalent
 * to a round trip through the configured data converter. The SDK may call it on workflow or
 * activity threads, including during replay. Object identity must not affect workflow behavior.
 *
 * <p>Currently, only local activities use this interface. Input batches use scheduling-time
 * snapshots when every non-null argument is copyable. Compatible requested types use fresh copies;
 * other types use the data converter. Copying is independent of marker options; omitted inputs
 * avoid serialization unless conversion or explicit payload access requires it. Outputs remain
 * serialized for history and deserialized on replay. Other execution paths, including eager
 * workflows and eager activities, do not yet use this interface.
 *
 * @param <T> the type of the copied value.
 */
@Experimental
public interface Copyable<T extends Copyable<T>> {
  /**
   * Returns an independent copy satisfying this interface's contract.
   *
   * @return a non-null copy, or {@code this} for a deeply immutable value.
   */
  T copy();
}
