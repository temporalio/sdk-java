package io.temporal.internal.payload.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.google.protobuf.ByteString;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.sdk.v1.ExternalStorageReference;
import io.temporal.common.converter.EncodingKeys;
import io.temporal.payload.storage.StorageDriverClaim;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

/** Tests external storage reference encoding and decoding. */
public class ExternalStorageReferencesTest {

  @Test
  public void currentFormatRoundTrips() {
    Map<String, String> claimData = new HashMap<>();
    claimData.put("bucket", "my-bucket");
    claimData.put("key", "abc123");
    StorageDriverClaim claim = new StorageDriverClaim(claimData);

    Payload reference = ExternalStorageReferences.toReferencePayload("driver-1", claim, 4096L);

    assertEquals(1, reference.getExternalPayloadsCount());
    assertEquals(4096L, reference.getExternalPayloads(0).getSizeBytes());
    assertEquals(
        "json/protobuf",
        reference.getMetadataMap().get(EncodingKeys.METADATA_ENCODING_KEY).toStringUtf8());

    ExternalStorageReferences.ParsedReference parsed =
        ExternalStorageReferences.tryParseReference(reference);
    assertNotNull(parsed);
    assertEquals("driver-1", parsed.driverName);
    assertEquals(claim, parsed.claim);
  }

  @Test
  public void inlinePayloadIsNotAReference() {
    Payload inline =
        Payload.newBuilder()
            .putMetadata(EncodingKeys.METADATA_ENCODING_KEY, ByteString.copyFromUtf8("json/plain"))
            .setData(ByteString.copyFromUtf8("\"hello\""))
            .build();
    assertNull(ExternalStorageReferences.tryParseReference(inline));
  }

  /**
   * {@code external_payloads} records the original size for the server and is not part of the
   * exchange contract, so a producer that omits it still writes a readable reference.
   */
  @Test
  public void referenceWithoutExternalPayloadsIsStillAReference() {
    Payload reference =
        Payload.newBuilder()
            .putMetadata(
                EncodingKeys.METADATA_ENCODING_KEY, ByteString.copyFromUtf8("json/protobuf"))
            .putMetadata(
                EncodingKeys.METADATA_MESSAGE_TYPE_KEY,
                ByteString.copyFromUtf8(ExternalStorageReference.getDescriptor().getFullName()))
            .setData(ByteString.copyFromUtf8("{\"driverName\":\"driver-1\"}"))
            .build();

    ExternalStorageReferences.ParsedReference parsed =
        ExternalStorageReferences.tryParseReference(reference);
    assertNotNull(parsed);
    assertEquals("driver-1", parsed.driverName);
  }

  @Test
  public void payloadWithReferenceMessageTypeButForeignEncodingIsNotAReference() {
    Payload foreign =
        Payload.newBuilder()
            .putMetadata(EncodingKeys.METADATA_ENCODING_KEY, ByteString.copyFromUtf8("json/plain"))
            .putMetadata(
                EncodingKeys.METADATA_MESSAGE_TYPE_KEY,
                ByteString.copyFromUtf8(ExternalStorageReference.getDescriptor().getFullName()))
            .setData(ByteString.copyFromUtf8("{\"driverName\":\"driver-1\"}"))
            .addExternalPayloads(
                Payload.ExternalPayloadDetails.newBuilder().setSizeBytes(4096L).build())
            .build();

    assertNull(ExternalStorageReferences.tryParseReference(foreign));
  }

  @Test
  public void payloadWithExternalPayloadsButForeignMessageTypeIsNotAReference() {
    Payload foreign =
        Payload.newBuilder()
            .putMetadata(
                EncodingKeys.METADATA_ENCODING_KEY, ByteString.copyFromUtf8("json/protobuf"))
            .putMetadata(
                EncodingKeys.METADATA_MESSAGE_TYPE_KEY,
                ByteString.copyFromUtf8("some.other.sdk.v1.ExternalStorageReference"))
            .setData(ByteString.copyFromUtf8("{\"foo\":1}"))
            .addExternalPayloads(
                Payload.ExternalPayloadDetails.newBuilder().setSizeBytes(4096L).build())
            .build();

    assertNull(ExternalStorageReferences.tryParseReference(foreign));
  }

  /**
   * References written by other SDKs must stay readable, so parsing tolerates snake_case field
   * names and fields added to the proto after this release.
   */
  @Test
  public void parsesReferenceWrittenByAnotherSdk() {
    Payload reference =
        Payload.newBuilder()
            .putMetadata(
                EncodingKeys.METADATA_ENCODING_KEY, ByteString.copyFromUtf8("json/protobuf"))
            .putMetadata(
                EncodingKeys.METADATA_MESSAGE_TYPE_KEY,
                ByteString.copyFromUtf8(ExternalStorageReference.getDescriptor().getFullName()))
            .setData(
                ByteString.copyFromUtf8(
                    "{\"driver_name\":\"driver-1\",\"claim_data\":{\"key\":\"abc123\"},"
                        + "\"field_added_later\":\"ignored\"}"))
            .addExternalPayloads(
                Payload.ExternalPayloadDetails.newBuilder().setSizeBytes(4096L).build())
            .build();

    ExternalStorageReferences.ParsedReference parsed =
        ExternalStorageReferences.tryParseReference(reference);
    assertNotNull(parsed);
    assertEquals("driver-1", parsed.driverName);
    assertEquals(new StorageDriverClaim(Collections.singletonMap("key", "abc123")), parsed.claim);
  }
}
