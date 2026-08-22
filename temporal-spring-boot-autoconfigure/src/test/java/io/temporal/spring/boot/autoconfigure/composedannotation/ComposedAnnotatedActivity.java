package io.temporal.spring.boot.autoconfigure.composedannotation;

import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface ComposedAnnotatedActivity {
  String execute(String input);
}
