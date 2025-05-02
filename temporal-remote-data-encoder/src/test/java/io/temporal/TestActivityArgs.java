package io.temporal;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface TestActivityArgs {
  @ActivityMethod
  int execute(String arg1, boolean arg2);
}
