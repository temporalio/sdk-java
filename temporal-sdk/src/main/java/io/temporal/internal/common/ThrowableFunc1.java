package io.temporal.internal.common;

public interface ThrowableFunc1<T, R, E extends Throwable> {
  R apply(T t) throws E;
}
