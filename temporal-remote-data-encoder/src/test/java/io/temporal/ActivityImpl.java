package io.temporal;

public class ActivityImpl implements TestActivityArgs {
  public static final int ACTIVITY_RESULT = 500;

  @Override
  public int execute(String arg1, boolean arg2) {
    return ACTIVITY_RESULT;
  }
}
