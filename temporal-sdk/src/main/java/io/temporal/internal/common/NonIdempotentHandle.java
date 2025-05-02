package io.temporal.internal.common;

/**
 * This interface signifies that the implementation of {@link #close()} method may and likely is not
 * idempotent, which is in agreement with {@link AutoCloseable#close()} contract. It also narrows
 * {@link AutoCloseable#close()} contract to not throw any checked exceptions, making it more
 * convenient to use in try-with-resources blocks.
 */
public interface NonIdempotentHandle extends AutoCloseable {
  @Override
  void close();
}
