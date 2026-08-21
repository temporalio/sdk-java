package io.temporal.common.converter;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.ByteString;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.sdk.v1.ExternalStorageReference;
import io.temporal.internal.payload.storage.ExternalStorageNotConfiguredException;
import org.junit.Test;

/**
 * When external storage is not configured, an inbound reference payload reaching value
 * deserialization must fail with the clear {@code [TMPRL1105]} error instead of an opaque decoding
 * failure.
 */
public class ExternalStorageReferenceGuardTest {

  private final DataConverter dataConverter = DefaultDataConverter.newDefaultInstance();

  @Test
  public void referencePayloadWithoutConfiguredStorageThrows() {
    Payload reference =
        Payload.newBuilder()
            .putMetadata(
                EncodingKeys.METADATA_ENCODING_KEY, ByteString.copyFromUtf8("json/protobuf"))
            .putMetadata(
                EncodingKeys.METADATA_MESSAGE_TYPE_KEY,
                ByteString.copyFromUtf8(ExternalStorageReference.getDescriptor().getFullName()))
            .setData(ByteString.copyFromUtf8("{}"))
            .build();

    ExternalStorageNotConfiguredException e =
        assertThrows(
            ExternalStorageNotConfiguredException.class,
            () -> dataConverter.fromPayload(reference, String.class, String.class));
    assertTrue(e.getMessage(), e.getMessage().contains("[TMPRL1105]"));
  }

  @Test
  public void rawValueBypassesTheGuard() {
    Payload reference =
        Payload.newBuilder()
            .addExternalPayloads(
                Payload.ExternalPayloadDetails.newBuilder().setSizeBytes(1024).build())
            .build();

    RawValue raw = dataConverter.fromPayload(reference, RawValue.class, RawValue.class);
    assertTrue(raw.getPayload().getExternalPayloadsCount() > 0);
  }
}
