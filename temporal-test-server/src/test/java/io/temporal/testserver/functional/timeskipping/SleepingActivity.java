package io.temporal.testserver.functional.timeskipping;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface SleepingActivity {
  @ActivityMethod
  void sleep();
}
