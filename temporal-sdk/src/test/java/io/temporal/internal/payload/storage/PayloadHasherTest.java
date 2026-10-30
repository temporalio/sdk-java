package io.temporal.internal.payload.storage;

import static org.junit.Assert.assertEquals;

import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class PayloadHasherTest {
  @Test
  public void matchesSha256VectorsIncludingBinaryBytesAndLeadingZero() {
    byte[][] inputs = {
      new byte[0],
      "abc".getBytes(StandardCharsets.UTF_8),
      new byte[] {0, 1, (byte) 0x80, (byte) 0xFF}
    };
    String[] expected = {
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
      "0ff830e8c68aca18063bce54c3191d5c116a2dfe33249538b252746cb777ef10"
    };
    for (int i = 0; i < inputs.length; i++) {
      assertEquals(expected[i], PayloadHasher.sha256Hex(inputs[i]));
    }
  }
}
