package io.temporal.internal.payload.storage;

import com.google.protobuf.Message;
import io.temporal.api.common.v1.Header;
import io.temporal.api.common.v1.Payload;
import io.temporal.internal.payload.visitor.PayloadVisitorOptions;
import io.temporal.internal.payload.visitor.PayloadVisitors;
import io.temporal.payload.codec.PayloadCodec;
import io.temporal.payload.codec.PayloadCodecException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;

/**
 * Applies the payload codec to the references that replace stored payloads, so that a reference's
 * claim data is protected the same way an inline payload is.
 *
 * <p>The codec is called with one reference at a time, and must return exactly one payload. Each
 * reference has to stay its own payload in place of the payload it replaced, while a codec given a
 * list may combine its payloads, so references are never encoded or decoded as a batch. A null
 * codec means none is configured, so a marked reference must already be plain.
 */
final class ExternalStorageReferenceCodec {

  /**
   * Replaces every marked reference in {@code builder}, headers included, with the plain reference
   * it encodes. Which header producers opted into the codec is only known once the header is read,
   * after retrieval, but the marker shows that the writer's did.
   *
   * @return whether the message holds any reference to retrieve
   */
  static CompletableFuture<Boolean> decode(Message.Builder builder, @Nullable PayloadCodec codec) {
    AtomicBoolean found = new AtomicBoolean();
    PayloadVisitorOptions<Void> options =
        PayloadVisitorOptions.<Void>newBuilder(
                (context, payloads) ->
                    CompletableFuture.completedFuture(decode(payloads, codec, found)))
            .setSkipSearchAttributes(true)
            .build();
    return PayloadVisitors.visit(builder, options).thenApply(v -> found.get());
  }

  /**
   * Encodes and marks every plain reference in {@code builder}. Headers are skipped, since the
   * codec encodes a header field only when its producer opted in; see {@link
   * #encode(Header.Builder, Set, PayloadCodec)}.
   */
  static CompletableFuture<Void> encode(Message.Builder builder, @Nullable PayloadCodec codec) {
    PayloadVisitorOptions<Void> options =
        PayloadVisitorOptions.<Void>newBuilder(
                (context, payloads) -> CompletableFuture.completedFuture(encode(payloads, codec)))
            .setSkipSearchAttributes(true)
            .setSkipHeaders(true)
            .build();
    return PayloadVisitors.visit(builder, options);
  }

  /**
   * Encodes and marks the plain references in the fields of {@code header} named by {@code keys}.
   */
  static void encode(Header.Builder header, Set<String> keys, @Nullable PayloadCodec codec) {
    for (String key : keys) {
      Payload value = header.getFieldsMap().get(key);
      if (value != null && needsEncoding(value)) {
        header.putFields(key, encode(value, codec));
      }
    }
  }

  private static List<Payload> decode(
      List<Payload> payloads, @Nullable PayloadCodec codec, AtomicBoolean found) {
    if (payloads.stream().noneMatch(ExternalStorageReferences::isAnyReference)) {
      return payloads;
    }
    found.set(true);
    List<Payload> result = new ArrayList<>(payloads.size());
    for (Payload payload : payloads) {
      result.add(ExternalStorageReferences.isMarked(payload) ? decode(payload, codec) : payload);
    }
    return result;
  }

  private static Payload decode(Payload payload, @Nullable PayloadCodec codec) {
    // Decoded as it appears in history, marker included, since that is also what a codec server
    // receives.
    List<Payload> decoded =
        codec == null
            ? Collections.singletonList(payload)
            : codec.decode(Collections.singletonList(payload));
    if (decoded.size() != 1 || !ExternalStorageReferences.isReference(decoded.get(0))) {
      throw new UndecodableExternalStorageReferenceException();
    }
    return copyExternalPayloadDetails(payload, decoded.get(0));
  }

  private static List<Payload> encode(List<Payload> payloads, @Nullable PayloadCodec codec) {
    if (payloads.stream().noneMatch(ExternalStorageReferenceCodec::needsEncoding)) {
      return payloads;
    }
    List<Payload> result = new ArrayList<>(payloads.size());
    for (Payload payload : payloads) {
      result.add(needsEncoding(payload) ? encode(payload, codec) : payload);
    }
    return result;
  }

  private static Payload encode(Payload payload, @Nullable PayloadCodec codec) {
    List<Payload> encoded =
        codec == null
            ? Collections.singletonList(payload)
            : codec.encode(Collections.singletonList(payload));
    if (encoded.size() != 1) {
      throw new PayloadCodecException(
          "Payload codec returned "
              + encoded.size()
              + " payloads for one external storage reference");
    }
    return copyExternalPayloadDetails(payload, ExternalStorageReferences.mark(encoded.get(0)));
  }

  private static boolean needsEncoding(Payload payload) {
    return ExternalStorageReferences.isReference(payload)
        && !ExternalStorageReferences.isMarked(payload);
  }

  // The server reads offloaded sizes from the outermost payload, and a codec that builds a new
  // payload drops them.
  private static Payload copyExternalPayloadDetails(Payload from, Payload to) {
    if (to.getExternalPayloadsCount() > 0) {
      return to;
    }
    return to.toBuilder().addAllExternalPayloads(from.getExternalPayloadsList()).build();
  }

  private ExternalStorageReferenceCodec() {}
}
