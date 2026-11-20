package io.temporal.internal.payload.storage;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import io.temporal.api.common.v1.Payload;
import io.temporal.common.converter.EncodingKeys;
import io.temporal.payload.codec.PayloadCodec;
import io.temporal.payload.codec.PayloadCodecException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nonnull;

/**
 * Test codec that wraps each payload in a new one with its own encoding, as an encrypting codec
 * does, so an encoded reference no longer looks like a reference. Counts the references it encodes
 * and the marked payloads it decodes.
 */
public final class EnvelopeCodec implements PayloadCodec {
  private static final ByteString ENCODING = ByteString.copyFromUtf8("binary/test-envelope");

  public final AtomicInteger referenceEncodes = new AtomicInteger();
  public final AtomicInteger markedDecodes = new AtomicInteger();

  @Override
  @Nonnull
  public List<Payload> encode(@Nonnull List<Payload> payloads) {
    List<Payload> result = new ArrayList<>(payloads.size());
    for (Payload payload : payloads) {
      if (ExternalStorageReferences.isReference(payload)) {
        referenceEncodes.incrementAndGet();
      }
      result.add(
          Payload.newBuilder()
              .putMetadata(EncodingKeys.METADATA_ENCODING_KEY, ENCODING)
              .setData(payload.toByteString())
              .build());
    }
    return result;
  }

  @Override
  @Nonnull
  public List<Payload> decode(@Nonnull List<Payload> payloads) {
    List<Payload> result = new ArrayList<>(payloads.size());
    for (Payload payload : payloads) {
      if (ExternalStorageReferences.isMarked(payload)) {
        markedDecodes.incrementAndGet();
      }
      if (!ENCODING.equals(payload.getMetadataMap().get(EncodingKeys.METADATA_ENCODING_KEY))) {
        result.add(payload);
        continue;
      }
      try {
        result.add(Payload.parseFrom(payload.getData()));
      } catch (InvalidProtocolBufferException e) {
        throw new PayloadCodecException(e);
      }
    }
    return result;
  }
}
