package io.temporal.spring.boot.autoconfigure.gracefulshutdown;

import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface SlowActivity {

  void run();
}
