package io.temporal.common.converter;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.ByteString;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.failure.v1.Failure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.TemporalFailure;
import io.temporal.payload.codec.PayloadCodec;
import io.temporal.payload.codec.PayloadCodecException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

public class CodecDataConverterTest {
  public @Rule Timeout timeout = Timeout.seconds(10);

  private CodecDataConverter dataConverter;

  @Before
  public void setUp() {
    PrefixPayloadCodec prefixPayloadCodec = new PrefixPayloadCodec();
    this.dataConverter =
        new CodecDataConverter(
            DefaultDataConverter.newDefaultInstance(),
            Collections.singletonList(prefixPayloadCodec),
            true);
  }

  @Test
  public void testMessageAndStackTraceAreCorrectlyEncoded() {
    try {
      ApplicationFailure causeException =
          ApplicationFailure.newFailure("CauseException", "CauseExceptionType");
      throw ApplicationFailure.newFailureWithCause("Message", "Type", causeException);
    } catch (ApplicationFailure originalException) {
      Failure failure = dataConverter.exceptionToFailure(originalException);

      // Assert the failure's message and stack trace were correctly moved to encoded attributes
      assertEquals("Encoded failure", failure.getMessage());
      assertEquals("", failure.getStackTrace());
      assertTrue(failure.hasEncodedAttributes());

      // Assert this was also done on the cause
      assertEquals("Encoded failure", failure.getCause().getMessage());
      assertEquals("", failure.getCause().getStackTrace());
      assertTrue(failure.getCause().hasEncodedAttributes());

      // Assert encoded_attributes were actually encoded
      assertTrue(isEncoded(failure.getEncodedAttributes()));
      assertTrue(isEncoded(failure.getCause().getEncodedAttributes()));
    }
  }

  @Test
  public void testMessageAndStackTraceAreCorrectlyDecoded() {
    try {
      ApplicationFailure causeException =
          ApplicationFailure.newFailure("CauseException", "CauseExceptionType");
      throw ApplicationFailure.newFailureWithCause("Message", "Type", causeException);
    } catch (ApplicationFailure originalException) {
      Failure failure = dataConverter.exceptionToFailure(originalException);
      TemporalFailure decodedException =
          (TemporalFailure) dataConverter.failureToException(failure);

      assertEquals("Message", decodedException.getOriginalMessage());
      assertEquals(
          "CauseException", ((TemporalFailure) decodedException.getCause()).getOriginalMessage());

      assertEquals(
          originalException.getStackTrace()[0].toString(),
          decodedException.getStackTrace()[0].toString());
      assertEquals(
          originalException.getCause().getStackTrace()[0].toString(),
          decodedException.getCause().getStackTrace()[0].toString());
    }
  }

  @Test
  public void testDetailsAreEncoded() {
    Object[] details = new Object[] {"test", 123, new int[] {1, 2, 3}};

    ApplicationFailure originalException =
        ApplicationFailure.newFailure("Message", "Type", details);
    Failure failure = dataConverter.exceptionToFailure(originalException);
    Exception decodedException = dataConverter.failureToException(failure);

    // Assert details were actually encoded
    List<Payload> encodedDetailsPayloads =
        failure.getApplicationFailureInfo().getDetails().getPayloadsList();
    assertTrue(isEncoded(encodedDetailsPayloads.get(0)));
    assertTrue(isEncoded(encodedDetailsPayloads.get(1)));
    assertTrue(isEncoded(encodedDetailsPayloads.get(2)));

    // Assert details can be decoded
    Values decodedDetailsPayloads = ((ApplicationFailure) decodedException).getDetails();
    assertEquals("test", decodedDetailsPayloads.get(0, String.class, String.class));
    assertEquals((Integer) 123, decodedDetailsPayloads.get(1, Integer.class, Integer.class));
    assertArrayEquals(new int[] {1, 2, 3}, decodedDetailsPayloads.get(2, int[].class, int[].class));
  }

  @Test
  public void testRawValuePassThrough() {
    Payload p = Payload.newBuilder().setData(ByteString.copyFromUtf8("test")).build();
    Optional<Payloads> data = dataConverter.toPayloads(new RawValue(p));
    // Assert that the payload is still encoded
    assertTrue(isEncoded(data.get().getPayloads(0)));
    RawValue converted = dataConverter.fromPayloads(0, data, RawValue.class, RawValue.class);
    assertEquals(p, converted.getPayload());
  }

  static boolean isEncoded(Payload payload) {
    return payload.getData().startsWith(PrefixPayloadCodec.PREFIX);
  }

  private static final class PrefixPayloadCodec implements PayloadCodec {
    public static final ByteString PREFIX = ByteString.copyFromUtf8("ENCODED: ");

    @Override
    @Nonnull
    public List<Payload> encode(@Nonnull List<Payload> payloads) {
      return payloads.stream().map(this::encode).collect(Collectors.toList());
    }

    private Payload encode(Payload decodedPayload) {
      ByteString encodedData = PREFIX.concat(decodedPayload.getData());
      return decodedPayload.toBuilder().setData(encodedData).build();
    }

    @Override
    @Nonnull
    public List<Payload> decode(@Nonnull List<Payload> payloads) {
      return payloads.stream().map(this::decode).collect(Collectors.toList());
    }

    private Payload decode(Payload encodedPayload) {
      ByteString encodedData = encodedPayload.getData();
      if (!encodedData.startsWith(PREFIX))
        throw new PayloadCodecException("Payload is not correctly encoded");
      ByteString decodedData = encodedData.substring(PREFIX.size());
      return encodedPayload.toBuilder().setData(decodedData).build();
    }
  }
}
