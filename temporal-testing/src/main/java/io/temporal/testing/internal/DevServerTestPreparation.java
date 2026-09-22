package io.temporal.testing.internal;

import io.temporal.testing.internal.devserver.SdkJavaTestServerProfile;
import javax.annotation.Nonnull;

/** Download-only entry point used by sdk-java's {@code prepareDevServerTests} task. */
public final class DevServerTestPreparation {
  private DevServerTestPreparation() {}

  public static void main(@Nonnull String[] args) {
    System.out.println(
        "Prepared Temporal CLI at " + SdkJavaTestServerProfile.prepare().toAbsolutePath());
  }
}
