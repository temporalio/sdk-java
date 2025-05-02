package io.temporal.internal.sync;

enum Status {
  CREATED,
  RUNNING,
  YIELDED,
  EVALUATING,
  DONE
}
