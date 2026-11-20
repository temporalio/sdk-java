package io.temporal.internal.payload.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.google.common.base.Throwables;
import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import io.temporal.api.common.v1.Header;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.workflowservice.v1.PollActivityTaskQueueResponse;
import io.temporal.common.converter.EncodingKeys;
import io.temporal.payload.codec.PayloadCodec;
import io.temporal.payload.codec.PayloadCodecException;
import io.temporal.payload.storage.StorageDriverClaim;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import org.junit.Test;

public class ExternalStorageReferenceCodecTest {

  private final EnvelopeCodec codec = new EnvelopeCodec();

  @Test
  public void encodeThenDecodeRoundTripsReferencesAndLeavesOtherPayloadsAlone() {
    Payload reference = reference("a");
    Payload ordinary = ordinary("plain");
    Payloads.Builder message = Payloads.newBuilder().addPayloads(reference).addPayloads(ordinary);

    encode(message, codec);

    Payload encoded = message.getPayloads(0);
    assertTrue(ExternalStorageReferences.isMarked(encoded));
    assertFalse(ExternalStorageReferences.isReference(encoded));
    // The codec drops them, so they are restored onto the outermost payload.
    assertEquals(reference.getExternalPayloadsList(), encoded.getExternalPayloadsList());
    assertEquals(ordinary, message.getPayloads(1));

    assertTrue(decode(message, codec));

    assertEquals(reference, message.getPayloads(0));
    assertEquals(ordinary, message.getPayloads(1));
  }

  @Test
  public void decodeReportsWhetherAnyReferenceNeedsRetrieving() {
    assertFalse(decode(Payloads.newBuilder().addPayloads(ordinary("plain")), codec));
    assertTrue(decode(Payloads.newBuilder().addPayloads(reference("a")), codec));
  }

  @Test
  public void withoutACodecReferencesAreMarkedAndStillPlain() {
    Payloads.Builder message = Payloads.newBuilder().addPayloads(reference("a"));

    encode(message, null);

    assertTrue(ExternalStorageReferences.isMarked(message.getPayloads(0)));
    assertTrue(ExternalStorageReferences.isReference(message.getPayloads(0)));
    assertTrue(decode(message, null));
    assertTrue(ExternalStorageReferences.isReference(message.getPayloads(0)));
  }

  @Test
  public void encodeLeavesAMarkedReferenceAlone() {
    Payload marked = ExternalStorageReferences.mark(reference("a"));
    Payloads.Builder message = Payloads.newBuilder().addPayloads(marked);

    encode(message, codec);

    assertEquals(marked, message.getPayloads(0));
    assertEquals(0, codec.referenceEncodes.get());
  }

  @Test
  public void aMarkedPayloadThatIsNotAReferenceFailsToDecode() {
    Payloads.Builder message =
        Payloads.newBuilder().addPayloads(ExternalStorageReferences.mark(ordinary("x")));

    UndecodableExternalStorageReferenceException thrown =
        assertThrows(
            UndecodableExternalStorageReferenceException.class, () -> decode(message, null));
    assertTrue(thrown.getMessage().contains("TMPRL1106"));
  }

  @Test
  public void aReferenceDecodedWithoutTheCodecThatWroteItFailsToDecode() {
    Payloads.Builder message = Payloads.newBuilder().addPayloads(reference("a"));
    encode(message, codec);

    assertThrows(UndecodableExternalStorageReferenceException.class, () -> decode(message, null));
  }

  @Test
  public void aCodecReturningOtherThanOnePayloadFailsToEncode() {
    PayloadCodec duplicating =
        new PayloadCodec() {
          @Override
          public List<Payload> encode(List<Payload> payloads) {
            List<Payload> result = new ArrayList<>(payloads);
            result.addAll(payloads);
            return result;
          }

          @Override
          public List<Payload> decode(List<Payload> payloads) {
            return payloads;
          }
        };

    assertThrows(
        PayloadCodecException.class,
        () -> encode(Payloads.newBuilder().addPayloads(reference("a")), duplicating));
  }

  /**
   * Which header fields are encoded depends on their producer's opt-in, which a message walk can't
   * see. A marked header reference shows that its writer opted in, so it is decoded on read.
   */
  @Test
  public void headerReferencesAreDecodedButNotEncoded() {
    PollActivityTaskQueueResponse.Builder task =
        PollActivityTaskQueueResponse.newBuilder()
            .setHeader(Header.newBuilder().putFields("h", reference("header")))
            .setInput(Payloads.newBuilder().addPayloads(reference("input")));

    encode(task, codec);

    assertEquals(reference("header"), task.getHeader().getFieldsOrThrow("h"));
    assertTrue(ExternalStorageReferences.isMarked(task.getInput().getPayloads(0)));

    Payload encodedHeader =
        ExternalStorageReferences.mark(
            codec.encode(Collections.singletonList(reference("header"))).get(0));
    task.setHeader(Header.newBuilder().putFields("h", encodedHeader));

    assertTrue(decode(task, codec));

    assertTrue(ExternalStorageReferences.isReference(task.getHeader().getFieldsOrThrow("h")));
    assertEquals(reference("input"), task.getInput().getPayloads(0));
  }

  @Test
  public void headerEncodeOnlyEncodesTheNamedFields() {
    Header.Builder header =
        Header.newBuilder()
            .putFields("opted-in", reference("a"))
            .putFields("not-opted-in", reference("b"))
            .putFields("ordinary", ordinary("plain"));

    ExternalStorageReferenceCodec.encode(
        header, new HashSet<>(Arrays.asList("opted-in", "ordinary", "absent")), codec);

    Payload encoded = header.getFieldsOrThrow("opted-in");
    assertTrue(ExternalStorageReferences.isMarked(encoded));
    assertFalse(ExternalStorageReferences.isReference(encoded));
    assertEquals(reference("a").getExternalPayloadsList(), encoded.getExternalPayloadsList());
    assertEquals(reference("b"), header.getFieldsOrThrow("not-opted-in"));
    assertEquals(ordinary("plain"), header.getFieldsOrThrow("ordinary"));
    assertFalse(header.containsFields("absent"));
  }

  private static Payload reference(String key) {
    return ExternalStorageReferences.toReferencePayload(
        "test", new StorageDriverClaim(Collections.singletonMap("key", key)), 1234);
  }

  private static Payload ordinary(String data) {
    return Payload.newBuilder()
        .putMetadata(EncodingKeys.METADATA_ENCODING_KEY, ByteString.copyFromUtf8("json/plain"))
        .setData(ByteString.copyFromUtf8(data))
        .build();
  }

  private static boolean decode(Message.Builder message, PayloadCodec codec) {
    return join(() -> ExternalStorageReferenceCodec.decode(message, codec));
  }

  private static void encode(Message.Builder message, PayloadCodec codec) {
    join(() -> ExternalStorageReferenceCodec.encode(message, codec));
  }

  private static <T> T join(Supplier<CompletableFuture<T>> operation) {
    try {
      return operation.get().join();
    } catch (CompletionException e) {
      Throwables.throwIfUnchecked(e.getCause());
      throw e;
    }
  }
}
