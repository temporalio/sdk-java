package io.temporal.aws.lambda;

import java.time.Duration;

interface TimedShutdownHook extends Runnable {
  void run(Duration timeout);
}
