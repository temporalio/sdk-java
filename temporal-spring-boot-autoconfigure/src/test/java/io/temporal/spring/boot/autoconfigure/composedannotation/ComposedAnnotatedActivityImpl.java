package io.temporal.spring.boot.autoconfigure.composedannotation;

@ComposedActivityImpl
public class ComposedAnnotatedActivityImpl implements ComposedAnnotatedActivity {

  @Override
  public String execute(String input) {
    return "composed-activity:" + input;
  }
}
