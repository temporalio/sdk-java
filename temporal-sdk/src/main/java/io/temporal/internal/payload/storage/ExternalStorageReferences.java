package io.temporal.internal.payload.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import com.google.protobuf.util.JsonFormat;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.sdk.v1.ExternalStorageReference;
import io.temporal.common.converter.EncodingKeys;
import io.temporal.payload.storage.StorageDriverClaim;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nonnull;

final class ExternalStorageReferences {
  private static final String ENCODING_PROTOBUF_JSON = "json/protobuf";
  private static final String ENCODING_LEGACY = "json/external-storage-reference";
  private static final String REFERENCE_MESSAGE_TYPE =
      ExternalStorageReference.getDescriptor().getFullName();

  private static final JsonFormat.Printer PRINTER = JsonFormat.printer();
  private static final JsonFormat.Parser PARSER = JsonFormat.parser().ignoringUnknownFields();
  private static final ObjectMapper LEGACY_MAPPER = new ObjectMapper();

  static final class ParsedReference {
    final String driverName;
    final StorageDriverClaim claim;

    ParsedReference(String driverName, StorageDriverClaim claim) {
      this.driverName = driverName;
      this.claim = claim;
    }
  }

  static boolean isReference(@Nonnull Payload payload) {
    if (payload.getExternalPayloadsCount() > 0) {
      return true;
    }
    ByteString encoding = payload.getMetadataMap().get(EncodingKeys.METADATA_ENCODING_KEY);
    return encoding != null && ENCODING_LEGACY.equals(encoding.toStringUtf8());
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

  static ParsedReference fromReferencePayload(@Nonnull Payload payload) {
    ByteString encoding = payload.getMetadataMap().get(EncodingKeys.METADATA_ENCODING_KEY);
    if (encoding != null && ENCODING_LEGACY.equals(encoding.toStringUtf8())) {
      return parseLegacy(payload);
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

  private static ParsedReference parseLegacy(Payload payload) {
    JsonNode root;
    try {
      root = LEGACY_MAPPER.readTree(payload.getData().toByteArray());
    } catch (Exception e) {
      throw new IllegalArgumentException("Failed to parse legacy external storage reference", e);
    }
    String driverName = root.path("driver_name").asText();
    JsonNode claimDataNode = root.path("driver_claim").path("claim_data");
    Map<String, String> claimData = new LinkedHashMap<>();
    Iterator<Map.Entry<String, JsonNode>> claimDataFields = claimDataNode.fields();
    while (claimDataFields.hasNext()) {
      Map.Entry<String, JsonNode> claimDataField = claimDataFields.next();
      claimData.put(claimDataField.getKey(), claimDataField.getValue().asText());
    }
    return new ParsedReference(driverName, new StorageDriverClaim(claimData));
  }

  private ExternalStorageReferences() {}
}
