package io.temporal.testing.internal;

import io.temporal.testing.internal.devserver.SdkJavaTestServerProfile;

/** Process entry point that owns sdk-java's repository dev server for a Gradle invocation. */
public final class DevServerTestProcess {
  private DevServerTestProcess() {}

  public static void main(String[] args) throws Exception {
    try {
      SdkJavaTestServerProfile.start();
      System.out.println("READY");
      System.out.flush();
      while (System.in.read() != -1) {
        // The Gradle shared service keeps stdin open for the lifetime of the build.
      }
    } finally {
      SdkJavaTestServerProfile.shutdown();
    }
  }
}
