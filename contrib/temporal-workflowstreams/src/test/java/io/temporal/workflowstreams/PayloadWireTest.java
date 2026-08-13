package io.temporal.workflowstreams;

import com.google.protobuf.ByteString;
import io.temporal.api.common.v1.Payload;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.workflowstreams.internal.PayloadWire;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.Assert;
import org.junit.Test;

public class PayloadWireTest {
  private static final DataConverter DC = DefaultDataConverter.STANDARD_INSTANCE;

  @Test
  public void testPayloadWireRoundTrip() {
    Payload payload = DC.toPayload("hello").get();

    String wire = PayloadWire.encode(payload);
    Payload got = PayloadWire.decode(wire);
    Assert.assertEquals(payload, got);

    // The decoded payload still carries its encoding metadata so a consumer can
    // decode it back to the original value.
    String s = DC.fromPayload(got, String.class, String.class);
    Assert.assertEquals("hello", s);
  }

  @Test
  public void testPayloadWireFormatIsBase64OfProto() throws Exception {
    Payload payload =
        Payload.newBuilder()
            .putMetadata("encoding", ByteString.copyFromUtf8("json/plain"))
            .setData(ByteString.copyFromUtf8("\"hi\""))
            .build();
    String wire = PayloadWire.encode(payload);

    // Wire format is base64-of-marshaled-proto; decoding base64 then proto must
    // reproduce the payload. This is the contract shared with the Go, Python, and
    // TypeScript packages.
    byte[] raw = Base64.getDecoder().decode(wire);
    Payload decoded = Payload.parseFrom(raw);
    Assert.assertEquals(payload, decoded);
  }

  @Test
  public void testDecodePayloadWireRejectsBadInput() {
    try {
      PayloadWire.decode("not valid base64!!!");
      Assert.fail("unreachable");
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test
  public void testBinaryPayloadRoundTrip() {
    byte[] original = new byte[] {0x00, 0x01, (byte) 0xff};
    Payload payload = DC.toPayload(original).get();

    String wire = PayloadWire.encode(payload);
    Payload got = PayloadWire.decode(wire);

    byte[] b = DC.fromPayload(got, byte[].class, byte[].class);
    Assert.assertArrayEquals(original, b);
  }

  @Test
  public void testWireSize() {
    Payload payload =
        Payload.newBuilder().setData(ByteString.copyFrom("x", StandardCharsets.UTF_8)).build();
    String wire = PayloadWire.encode(payload);
    Assert.assertEquals(wire.length() + "topic".length(), PayloadWire.wireSize(wire, "topic"));
  }
}
