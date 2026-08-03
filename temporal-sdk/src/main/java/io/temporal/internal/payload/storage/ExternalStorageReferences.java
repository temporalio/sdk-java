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
   */
  static @Nullable ParsedReference tryParseReference(@Nonnull Payload payload) {
    if (payload.getExternalPayloadsCount() == 0) {
      return null;
    }
    ByteString messageType = payload.getMetadataMap().get(EncodingKeys.METADATA_MESSAGE_TYPE_KEY);
    if (messageType == null || !REFERENCE_MESSAGE_TYPE.equals(messageType.toStringUtf8())) {
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

  private ExternalStorageReferences() {}
}
