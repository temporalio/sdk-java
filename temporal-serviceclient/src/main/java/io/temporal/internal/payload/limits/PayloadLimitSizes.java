package io.temporal.internal.payload.limits;

import com.google.protobuf.MessageLite;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;

/**
 * Field-size measurements, mirroring how the Temporal server measures each field. Size is the
 * serialized proto size ({@link MessageLite#getSerializedSize()}) except for the map-aggregate
 * helpers, which mirror the server's {@code sum(len(key) + ...)} accounting. Called from the
 * generated {@code GeneratedPayloadLimitValidator}.
 */
final class PayloadLimitSizes {
  private PayloadLimitSizes() {}

  /**
   * Serialized proto size of a single message (e.g. Payload, Payloads, Memo, or a whole Failure).
   */
  static long serializedSize(MessageLite message) {
    return message.getSerializedSize();
  }

  /**
   * Sum of serialized proto sizes over a collection of messages (e.g. repeated Payload/Failure).
   */
  static long serializedSizeSum(Collection<? extends MessageLite> messages) {
    long total = 0;
    for (MessageLite m : messages) {
      total += m.getSerializedSize();
    }
    return total;
  }

  /**
   * Aggregate size of a marker-style {@code map<string, Payloads>}, mirroring the server's {@code
   * sum(len(key) + payloads.Size())} accounting (e.g. {@code
   * RecordMarkerCommandAttributes.details}).
   */
  static long mapPayloadsSum(Map<String, Payloads> entries) {
    long total = 0;
    for (Map.Entry<String, Payloads> e : entries.entrySet()) {
      total += utf8Length(e.getKey()) + e.getValue().getSerializedSize();
    }
    return total;
  }

  /**
   * Aggregate size of a search-attribute/memo-style {@code map<string, Payload>}, mirroring the
   * server's {@code sum(len(key) + len(payload.data))} accounting — note the server counts the
   * <b>raw data</b> length here, not the serialized payload size (e.g. {@code
   * UpsertWorkflowSearchAttributes.indexed_fields}).
   */
  static long mapPayloadDataSum(Map<String, Payload> entries) {
    long total = 0;
    for (Map.Entry<String, Payload> e : entries.entrySet()) {
      total += utf8Length(e.getKey()) + e.getValue().getData().size();
    }
    return total;
  }

  private static int utf8Length(String s) {
    return s.getBytes(StandardCharsets.UTF_8).length;
  }
}
