package io.temporal.internal.nexus;

import com.google.common.reflect.TypeToken;
import com.google.protobuf.InvalidProtocolBufferException;
import io.nexusrpc.handler.HandlerException;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DataConverterException;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.converter.EncodedValuesTest;
import io.temporal.common.converter.PayloadValidationException;
import io.temporal.failure.ApplicationFailure;
import io.temporal.payload.codec.PayloadCodecException;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;
import org.junit.Assert;
import org.junit.Test;

public class PayloadSerializerTest {
  static DataConverter dataConverter = DefaultDataConverter.STANDARD_INSTANCE;
  PayloadSerializer payloadSerializer = new PayloadSerializer(dataConverter);

  @Test
  public void testPayload() {
    String original = "test";
    PayloadSerializer.Content content = payloadSerializer.serialize(original);
    Assert.assertEquals(original, payloadSerializer.deserialize(content, String.class));
  }

  @Test
  public void testNull() {
    PayloadSerializer.Content content = payloadSerializer.serialize(null);
    Assert.assertNull(payloadSerializer.deserialize(content, String.class));
  }

  @Test
  public void testInteger() {
    PayloadSerializer.Content content = payloadSerializer.serialize(1);
    Assert.assertEquals(1, payloadSerializer.deserialize(content, Integer.class));
  }

  @Test
  public void testArray() {
    String[] cars = {"test", "nexus", "serialization"};
    PayloadSerializer.Content content = payloadSerializer.serialize(cars);
    Assert.assertArrayEquals(
        cars, (String[]) payloadSerializer.deserialize(content, String[].class));
  }

  @Test
  public void testHashMap() {
    Map<String, EncodedValuesTest.Pair> map =
        Collections.singletonMap("key", new EncodedValuesTest.Pair(1, "hello"));
    PayloadSerializer.Content content = payloadSerializer.serialize(map);
    Map<String, EncodedValuesTest.Pair> newMap =
        (Map<String, EncodedValuesTest.Pair>)
            payloadSerializer.deserialize(
                content, (new TypeToken<Map<String, EncodedValuesTest.Pair>>() {}).getType());
    Assert.assertTrue(newMap.get("key") instanceof EncodedValuesTest.Pair);
  }

  @Test
  public void testProto() {
    WorkflowExecution exec =
        WorkflowExecution.newBuilder().setWorkflowId("id").setRunId("runId").build();
    PayloadSerializer.Content content = payloadSerializer.serialize(exec);
    Assert.assertEquals(exec, payloadSerializer.deserialize(content, WorkflowExecution.class));
  }

  @Test
  public void testDeserializeMalformedPayloadIsNonRetryableBadRequest() {
    // Truncated varint, so these bytes are not a Payload and never will be.
    PayloadSerializer.Content content =
        PayloadSerializer.Content.newBuilder()
            .setData(new byte[] {(byte) 0xff, (byte) 0xff, (byte) 0xff})
            .build();

    HandlerException e =
        Assert.assertThrows(
            HandlerException.class, () -> payloadSerializer.deserialize(content, String.class));
    Assert.assertEquals(HandlerException.ErrorType.BAD_REQUEST, e.getErrorType());
    Assert.assertFalse(e.isRetryable());
    Assert.assertTrue(e.getCause() instanceof InvalidProtocolBufferException);
  }

  @Test
  public void testDeserializeWrongTypeIsNonRetryableBadRequest() {
    PayloadSerializer.Content content = payloadSerializer.serialize("not an integer");

    HandlerException e =
        Assert.assertThrows(
            HandlerException.class, () -> payloadSerializer.deserialize(content, Integer.class));
    Assert.assertEquals(HandlerException.ErrorType.BAD_REQUEST, e.getErrorType());
    Assert.assertFalse(e.isRetryable());
    Assert.assertTrue(e.getCause() instanceof DataConverterException);
  }

  @Test
  public void testDeserializeHandlerExceptionIsPropagatedAsIs() {
    HandlerException original =
        new HandlerException(HandlerException.ErrorType.NOT_FOUND, new RuntimeException("nope"));
    PayloadSerializer serializer = failingSerializer(null, original);
    PayloadSerializer.Content content = payloadSerializer.serialize("test");

    Assert.assertSame(
        original,
        Assert.assertThrows(
            HandlerException.class, () -> serializer.deserialize(content, String.class)));
  }

  @Test
  public void testDeserializeApplicationFailureIsPropagatedAsIs() {
    ApplicationFailure original = ApplicationFailure.newNonRetryableFailure("bad", "TestFailure");
    PayloadSerializer serializer = failingSerializer(null, original);
    PayloadSerializer.Content content = payloadSerializer.serialize("test");

    Assert.assertSame(
        original,
        Assert.assertThrows(
            ApplicationFailure.class, () -> serializer.deserialize(content, String.class)));
  }

  @Test
  public void testDeserializeNonRetryablePayloadValidationErrorIsNonRetryableBadRequest() {
    // The converter understood the input and rejected it, which makes this the caller's fault.
    ApplicationFailure original =
        PayloadValidationException.newPayloadValidationException(
            Collections.singletonList(Collections.singletonMap("name", "must not be empty")));
    PayloadSerializer serializer = failingSerializer(null, original);
    PayloadSerializer.Content content = payloadSerializer.serialize("test");

    HandlerException e =
        Assert.assertThrows(
            HandlerException.class, () -> serializer.deserialize(content, String.class));
    Assert.assertEquals(HandlerException.ErrorType.BAD_REQUEST, e.getErrorType());
    Assert.assertFalse(e.isRetryable());
    Assert.assertEquals("invalid operation input", e.getMessage());
    // The converter's own message is not in the wrapper, so it has to survive on the cause.
    Assert.assertTrue(
        "expected an ApplicationFailure cause, got " + e.getCause(),
        e.getCause() instanceof ApplicationFailure);
    ApplicationFailure causeFailure = (ApplicationFailure) e.getCause();
    Assert.assertSame(original, causeFailure);
    Assert.assertEquals(PayloadSerializer.PAYLOAD_VALIDATION_ERROR_TYPE, causeFailure.getType());
    Assert.assertTrue(causeFailure.isNonRetryable());
    Assert.assertEquals("Payload validation failed", causeFailure.getOriginalMessage());
    Assert.assertEquals(1, causeFailure.getDetails().getSize());
  }

  @Test
  public void testDeserializeNonRetryableOtherApplicationFailureTypeIsPropagatedAsIs() {
    // Only the PayloadValidationError type opts into BAD_REQUEST, everything else keeps the
    // non-retryable INTERNAL handling NexusTaskHandlerImpl applies.
    ApplicationFailure original =
        ApplicationFailure.newNonRetryableFailure("invalid input", "SomeOtherValidationError");
    PayloadSerializer serializer = failingSerializer(null, original);
    PayloadSerializer.Content content = payloadSerializer.serialize("test");

    Assert.assertSame(
        original,
        Assert.assertThrows(
            ApplicationFailure.class, () -> serializer.deserialize(content, String.class)));
  }

  @Test
  public void testDeserializeRetryablePayloadValidationErrorIsPropagatedAsIs() {
    // A retryable failure may succeed on a retry, so the type alone must not make it a
    // non-retryable BAD_REQUEST.
    ApplicationFailure original =
        ApplicationFailure.newFailure(
            "invalid input", PayloadSerializer.PAYLOAD_VALIDATION_ERROR_TYPE);
    PayloadSerializer serializer = failingSerializer(null, original);
    PayloadSerializer.Content content = payloadSerializer.serialize("test");

    Assert.assertSame(
        original,
        Assert.assertThrows(
            ApplicationFailure.class, () -> serializer.deserialize(content, String.class)));
  }

  @Test
  public void testDeserializeTransientFailureIsNotTranslated() {
    // A payload codec outage is not the caller's fault and may succeed on a retry, so it must not
    // be flattened into a non-retryable BAD_REQUEST.
    PayloadCodecException original = new PayloadCodecException("codec server unavailable");
    PayloadSerializer serializer = failingSerializer(null, original);
    PayloadSerializer.Content content = payloadSerializer.serialize("test");

    Assert.assertSame(
        original,
        Assert.assertThrows(
            PayloadCodecException.class, () -> serializer.deserialize(content, String.class)));
  }

  @Test
  public void testDeserializeUnsupportedTypeIsNonRetryableInternal() {
    PayloadSerializer.Content content = payloadSerializer.serialize("test");
    Type unsupported =
        new GenericArrayType() {
          @Override
          public Type getGenericComponentType() {
            return String.class;
          }
        };

    HandlerException e =
        Assert.assertThrows(
            HandlerException.class, () -> payloadSerializer.deserialize(content, unsupported));
    // A handler definition problem, so not reported as the caller's fault, but still permanent.
    Assert.assertEquals(HandlerException.ErrorType.INTERNAL, e.getErrorType());
    Assert.assertFalse(e.isRetryable());
  }

  @Test
  public void testSerializeFailureIsPropagatedAsIs() {
    // Result serialization failures are not translated, so they keep the default retryable
    // INTERNAL handling applied by NexusTaskHandlerImpl.
    RuntimeException original = new RuntimeException("cannot serialize");
    PayloadSerializer serializer = failingSerializer(original, null);

    Assert.assertSame(
        original, Assert.assertThrows(RuntimeException.class, () -> serializer.serialize("test")));
  }

  @Test
  public void testSerializeApplicationFailureIsPropagatedAsIs() {
    // Result serialization is not translated either, which means a converter can still pick the
    // outcome: NexusTaskHandlerImpl turns this into a non-retryable INTERNAL handler error.
    ApplicationFailure original = ApplicationFailure.newNonRetryableFailure("bad", "TestFailure");
    PayloadSerializer serializer = failingSerializer(original, null);

    Assert.assertSame(
        original,
        Assert.assertThrows(ApplicationFailure.class, () -> serializer.serialize("test")));
  }

  /** A serializer whose underlying data converter fails in the requested direction. */
  private static PayloadSerializer failingSerializer(
      @Nullable RuntimeException onSerialize, @Nullable RuntimeException onDeserialize) {
    return new PayloadSerializer(
        new DataConverter() {
          @Override
          public <T> Optional<Payload> toPayload(T value) {
            if (onSerialize != null) {
              throw onSerialize;
            }
            return dataConverter.toPayload(value);
          }

          @Override
          public <T> T fromPayload(Payload payload, Class<T> valueClass, Type valueType) {
            if (onDeserialize != null) {
              throw onDeserialize;
            }
            return dataConverter.fromPayload(payload, valueClass, valueType);
          }

          @Override
          public Optional<Payloads> toPayloads(Object... values) {
            return dataConverter.toPayloads(values);
          }

          @Override
          public <T> T fromPayloads(
              int index, Optional<Payloads> content, Class<T> valueType, Type valueGenericType) {
            return dataConverter.fromPayloads(index, content, valueType, valueGenericType);
          }
        });
  }
}
