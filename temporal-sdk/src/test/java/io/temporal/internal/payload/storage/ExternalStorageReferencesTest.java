package io.temporal.internal.payload.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.ByteString;
import io.temporal.api.common.v1.Payload;
import io.temporal.common.converter.EncodingKeys;
import io.temporal.payload.storage.StorageDriverClaim;
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

    assertTrue(ExternalStorageReferences.isReference(reference));
    assertEquals(1, reference.getExternalPayloadsCount());
    assertEquals(4096L, reference.getExternalPayloads(0).getSizeBytes());
    assertEquals(
        "json/protobuf",
        reference.getMetadataMap().get(EncodingKeys.METADATA_ENCODING_KEY).toStringUtf8());

    ExternalStorageReferences.ParsedReference parsed =
        ExternalStorageReferences.fromReferencePayload(reference);
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
    assertFalse(ExternalStorageReferences.isReference(inline));
  }

  @Test
  public void fromReferencePayloadRejectsNonReference() {
    Payload inline =
        Payload.newBuilder()
            .putMetadata(EncodingKeys.METADATA_ENCODING_KEY, ByteString.copyFromUtf8("json/plain"))
            .setData(ByteString.copyFromUtf8("{\"foo\":1}"))
            .build();

    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> ExternalStorageReferences.fromReferencePayload(inline));
    assertTrue(e.getMessage().contains("not an external storage reference"));
  }
}
