package io.temporal.internal.task;

public interface ThreadConfigurator {
  void configure(Thread t);
}
