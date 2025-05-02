package io.temporal.spring.boot.autoconfigure.bytaskqueue;

import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface TestActivity {
  String execute(String input);
}
