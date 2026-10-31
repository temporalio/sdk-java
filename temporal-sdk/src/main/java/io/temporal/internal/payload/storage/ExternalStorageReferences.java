package io.temporal.internal.payload.storage;

import com.google.protobuf.ByteString;
import com.google.protobuf.util.JsonFormat;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.sdk.v1.ExternalStorageReference;
import io.temporal.common.converter.EncodingKeys;
import io.temporal.payload.storage.StorageDriverClaim;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

final class ExternalStorageReferences {
  private static final String ENCODING_PROTOBUF_JSON = "json/protobuf";
  private static final String REFERENCE_MESSAGE_TYPE =
      ExternalStorageReference.getDescriptor().getFullName();

  private static final JsonFormat.Printer PRINTER = JsonFormat.printer();
  private static final JsonFormat.Parser PARSER = JsonFormat.parser().ignoringUnknownFields();

  static final class ParsedReference {
    final String driverName;
    final StorageDriverClaim claim;

    ParsedReference(String driverName, StorageDriverClaim claim) {
      this.driverName = driverName;
      this.claim = claim;
    }
  }

  static Payload toReferencePayload(
      @Nonnull String driverName,
      @Nonnull StorageDriverClaim claim,
      long originalPayloadSizeBytes) {
    ExternalStorageReference reference =
        ExternalStorageReference.newBuilder()
            .setDriverName(driverName)
            .putAllClaimData(claim.getClaimData())
            .build();
    String json;
    try {
      json = PRINTER.print(reference);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to serialize external storage reference", e);
    }
    return Payload.newBuilder()
        .putMetadata(
            EncodingKeys.METADATA_ENCODING_KEY, ByteString.copyFromUtf8(ENCODING_PROTOBUF_JSON))
        .putMetadata(
            EncodingKeys.METADATA_MESSAGE_TYPE_KEY, ByteString.copyFromUtf8(REFERENCE_MESSAGE_TYPE))
        .setData(ByteString.copyFromUtf8(json))
        .addExternalPayloads(
            Payload.ExternalPayloadDetails.newBuilder()
                .setSizeBytes(originalPayloadSizeBytes)
                .build())
        .build();
  }

  /**
   * Returns the reference encoded in {@code payload}, or null if the payload is not an external
   * storage reference this SDK understands.
   *
   * <p>Only the encoding and message type identify a reference. {@code external_payloads} records
   * the original size for the server's benefit and is not part of the exchange contract, so a
   * producer that omits it still yields a readable reference.
   */
  static @Nullable ParsedReference tryParseReference(@Nonnull Payload payload) {
    if (!isReference(payload)) {
      return null;
    }
    ExternalStorageReference.Builder builder = ExternalStorageReference.newBuilder();
    try {
      PARSER.merge(payload.getData().toStringUtf8(), builder);
    } catch (Exception e) {
      throw new IllegalArgumentException("Failed to parse external storage reference", e);
    }
    ExternalStorageReference reference = builder.build();
    return new ParsedReference(
        reference.getDriverName(), new StorageDriverClaim(reference.getClaimDataMap()));
  }

  /** True if {@code payload} has an external storage reference encoding and message type. */
  static boolean isReference(Payload payload) {
    return hasMetadata(payload, EncodingKeys.METADATA_ENCODING_KEY, ENCODING_PROTOBUF_JSON)
        && hasMetadata(payload, EncodingKeys.METADATA_MESSAGE_TYPE_KEY, REFERENCE_MESSAGE_TYPE);
  }

  private static boolean hasMetadata(Payload payload, String key, String expected) {
    ByteString value = payload.getMetadataMap().get(key);
    return value != null && expected.equals(value.toStringUtf8());
  }

  private ExternalStorageReferences() {}
}
