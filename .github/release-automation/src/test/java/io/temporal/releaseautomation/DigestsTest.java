package io.temporal.releaseautomation;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class DigestsTest {
  @Test
  public void sha256UsesUtf8AndLowercaseHex() {
    assertEquals(
        "4c2ec6c321cf1e7ff2ebc3f02efb49505f7af84e58f2bb0a08c3c170af665f6b",
        Digests.sha256("Temporal ☃"));
  }
}
