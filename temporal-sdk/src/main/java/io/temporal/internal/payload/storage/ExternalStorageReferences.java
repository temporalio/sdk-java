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
  // A reference encoded by the payload codec can't be recognized by its encoding any more, so this
  // metadata, which codecs pass through, marks it.
  private static final String MARKER_KEY = "__temporal_external_storage_reference";
  private static final ByteString MARKER_VALUE = ByteString.copyFromUtf8("true");

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

  /** True if {@code payload} is marked as a reference, whether or not the codec encoded it. */
  static boolean isMarked(Payload payload) {
    return MARKER_VALUE.equals(payload.getMetadataMap().get(MARKER_KEY));
  }

  /** True if {@code payload} is a reference, plain or marked. */
  static boolean isAnyReference(Payload payload) {
    return isReference(payload) || isMarked(payload);
  }

  static Payload mark(Payload payload) {
    return payload.toBuilder().putMetadata(MARKER_KEY, MARKER_VALUE).build();
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
