package io.temporal.spring.boot.autoconfigure.byworkername;

import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface TestActivity {
  String execute(String input);
}
