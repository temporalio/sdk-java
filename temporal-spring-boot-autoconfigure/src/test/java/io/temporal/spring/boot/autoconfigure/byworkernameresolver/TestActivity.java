package io.temporal.spring.boot.autoconfigure.byworkernameresolver;

import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface TestActivity {
  String execute(String input);
}
