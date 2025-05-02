package io.temporal.workflow.shared;

import io.temporal.activity.Activity;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

public class NonSerializableException extends RuntimeException {
  @SuppressWarnings("unused")
  private final InputStream file; // gson chokes on this field

  public NonSerializableException() {
    try {
      file = new FileInputStream(File.createTempFile("foo", "bar"));
    } catch (IOException e) {
      throw Activity.wrap(e);
    }
  }
}
