package io.temporal.payload.codec;

import static org.junit.Assert.*;

import com.google.protobuf.ByteString;
import io.temporal.api.common.v1.Payload;
import io.temporal.common.converter.EncodingKeys;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class ZlibPayloadCodecTest {
  private final PayloadCodec codec = new ZlibPayloadCodec();

  @Test
  public void normalDeflation() {
    ByteString bytes = ByteString.copyFrom("11111111111", StandardCharsets.UTF_8);
    Payload originalPayload = Payload.newBuilder().setData(bytes).build();

    List<Payload> encodedPayloads = codec.encode(Collections.singletonList(originalPayload));
    Payload encodedPayload = encodedPayloads.get(0);
    ByteString metadata =
        encodedPayload.getMetadataOrDefault(EncodingKeys.METADATA_ENCODING_KEY, null);
    assertEquals(
        "The incoming string should be effectively deflatable",
        ZlibPayloadCodec.METADATA_ENCODING_ZLIB,
        metadata);

    List<Payload> decodedPayloads = codec.decode(Collections.singletonList(encodedPayload));
    Payload decodedPayload = decodedPayloads.get(0);
    assertEquals(originalPayload, decodedPayload);
  }

  /** Covers a situation when Zlib compression leads to a result that is larger that the original */
  @Test
  public void deflationInflates() {
    ByteString bytes = ByteString.copyFrom("notEffectivelyDeflatable", StandardCharsets.UTF_8);
    Payload originalPayload = Payload.newBuilder().setData(bytes).build();

    List<Payload> encodedPayloads = codec.encode(Collections.singletonList(originalPayload));
    Payload encodedPayload = encodedPayloads.get(0);
    ByteString metadata =
        encodedPayload.getMetadataOrDefault(EncodingKeys.METADATA_ENCODING_KEY, null);
    assertNull(
        "The incoming string can't be effectively deflated by Zlib, so the original payload without zlib encoding metadata should be returned",
        metadata);

    List<Payload> decodedPayloads = codec.decode(Collections.singletonList(encodedPayload));
    Payload decodedPayload = decodedPayloads.get(0);
    assertEquals(originalPayload, decodedPayload);
  }
}
