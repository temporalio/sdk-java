package io.temporal.workflowstreams;

import io.temporal.api.common.v1.Payload;
import io.temporal.common.converter.ByteArrayPayloadConverter;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.converter.EncodingKeys;
import io.temporal.workflowstreams.internal.PayloadWire;
import io.temporal.workflowstreams.internal.StreamPublisher;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

public class StreamPublisherTest {
  private static final DataConverter DC = DefaultDataConverter.STANDARD_INSTANCE;

  /** Records sent batches; when {@code failure} is set, sending throws it instead. */
  private static class RecordingSignal implements StreamPublisher.SignalFunction {
    final List<PublishInput> signals = new ArrayList<>();
    volatile RuntimeException failure;

    @Override
    public synchronized void send(PublishInput input) {
      if (failure != null) {
        throw failure;
      }
      signals.add(input);
    }

    synchronized List<PublishInput> recorded() {
      return new ArrayList<>(signals);
    }
  }

  private static StreamPublisher newPublisher(
      RecordingSignal signal, Duration batchInterval, int maxBatchSize, Duration maxRetry) {
    return new StreamPublisher(signal, DC, batchInterval, maxBatchSize, maxRetry);
  }

  private static StreamPublisher newPublisher(RecordingSignal signal) {
    return newPublisher(
        signal, Duration.ofSeconds(2), 0, WorkflowStreamConstants.DEFAULT_MAX_RETRY_DURATION);
  }

  private static String decodeItem(PublishInput input, int index) {
    Payload payload = PayloadWire.decode(input.items.get(index).data);
    return DC.fromPayload(payload, String.class, String.class);
  }

  private static void eventually(Duration timeout, Runnable assertion) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (true) {
      try {
        assertion.run();
        return;
      } catch (AssertionError e) {
        if (System.nanoTime() > deadline) {
          throw e;
        }
        Thread.sleep(5);
      }
    }
  }

  @Test
  public void testFlushSendsBufferedItems() {
    RecordingSignal signal = new RecordingSignal();
    StreamPublisher publisher = newPublisher(signal);
    publisher.publish("events", "a", false);
    publisher.publish("events", "b", false);

    publisher.flush();

    List<PublishInput> signals = signal.recorded();
    Assert.assertEquals(1, signals.size());
    Assert.assertEquals(2, signals.get(0).items.size());
    Assert.assertEquals(1, signals.get(0).sequence);
    Assert.assertFalse(signals.get(0).publisherId.isEmpty());
    Assert.assertEquals("a", decodeItem(signals.get(0), 0));
    Assert.assertEquals("b", decodeItem(signals.get(0), 1));

    publisher.close();
  }

  /**
   * Proves that the configured payload converters (not the default set) serialize each item. With
   * only the byte-array converter, a byte[] round-trips but a string has no converter and fails —
   * whereas the default set's JSON fallback would have accepted it.
   */
  @Test
  public void testPayloadConvertersDriveItemConversion() {
    RecordingSignal signal = new RecordingSignal();
    StreamPublisher publisher =
        new StreamPublisher(
            signal,
            new DefaultDataConverter(new ByteArrayPayloadConverter()),
            Duration.ofSeconds(2),
            0,
            WorkflowStreamConstants.DEFAULT_MAX_RETRY_DURATION);

    publisher.publish("events", "hi".getBytes(StandardCharsets.UTF_8), false);
    publisher.flush();
    List<PublishInput> signals = signal.recorded();
    Assert.assertEquals(1, signals.size());
    Payload payload = PayloadWire.decode(signals.get(0).items.get(0).data);
    Assert.assertEquals(
        "item must be serialized by the configured byte-array converter",
        "binary/plain",
        payload.getMetadataOrThrow(EncodingKeys.METADATA_ENCODING_KEY).toStringUtf8());
    publisher.close();

    // A string is unconvertible under the byte-array-only set, so the publish call itself
    // fails — the default set's JSON fallback would have accepted it. Conversion happens at
    // publish time so a bad value cannot poison the buffer and wedge the background flush
    // loop behind it.
    RecordingSignal signal2 = new RecordingSignal();
    StreamPublisher publisher2 =
        new StreamPublisher(
            signal2,
            new DefaultDataConverter(new ByteArrayPayloadConverter()),
            Duration.ofSeconds(2),
            0,
            WorkflowStreamConstants.DEFAULT_MAX_RETRY_DURATION);
    try {
      publisher2.publish("events", "not-bytes", false);
      Assert.fail("unreachable");
    } catch (RuntimeException expected) {
    }

    // The rejected value must not wedge the publisher: a valid item published afterwards
    // still ships.
    publisher2.publish("events", "ok".getBytes(StandardCharsets.UTF_8), false);
    publisher2.flush();
    Assert.assertEquals(1, signal2.recorded().size());
    publisher2.close();
  }

  @Test
  public void testFlushNoopWhenEmpty() {
    RecordingSignal signal = new RecordingSignal();
    StreamPublisher publisher = newPublisher(signal);
    publisher.flush();
    Assert.assertTrue(signal.recorded().isEmpty());
  }

  @Test
  public void testSequenceAdvancesAcrossFlushes() {
    RecordingSignal signal = new RecordingSignal();
    StreamPublisher publisher = newPublisher(signal);

    publisher.publish("t", "x", false);
    publisher.flush();
    publisher.publish("t", "y", false);
    publisher.flush();

    List<PublishInput> signals = signal.recorded();
    Assert.assertEquals(2, signals.size());
    Assert.assertEquals(1, signals.get(0).sequence);
    Assert.assertEquals(2, signals.get(1).sequence);
    Assert.assertEquals(signals.get(0).publisherId, signals.get(1).publisherId);

    publisher.close();
  }

  @Test
  public void testMaxBatchSizeTriggersFlush() throws InterruptedException {
    RecordingSignal signal = new RecordingSignal();
    // Long interval so only the size threshold can trigger a flush.
    StreamPublisher publisher =
        newPublisher(
            signal, Duration.ofHours(1), 2, WorkflowStreamConstants.DEFAULT_MAX_RETRY_DURATION);

    publisher.publish("t", "a", false);
    publisher.publish("t", "b", false); // reaches maxBatchSize -> flush

    eventually(Duration.ofSeconds(5), () -> Assert.assertEquals(1, signal.recorded().size()));

    publisher.close();
  }

  @Test
  public void testCloseDrainsBuffer() {
    RecordingSignal signal = new RecordingSignal();
    StreamPublisher publisher =
        newPublisher(
            signal, Duration.ofHours(1), 0, WorkflowStreamConstants.DEFAULT_MAX_RETRY_DURATION);

    publisher.publish("t", "a", false);
    publisher.close();

    List<PublishInput> signals = signal.recorded();
    Assert.assertEquals(1, signals.size());
    Assert.assertEquals(1, signals.get(0).items.size());
  }

  @Test
  public void testForceFlush() throws InterruptedException {
    RecordingSignal signal = new RecordingSignal();
    StreamPublisher publisher =
        newPublisher(
            signal, Duration.ofHours(1), 0, WorkflowStreamConstants.DEFAULT_MAX_RETRY_DURATION);

    publisher.publish("t", "a", true); // forceFlush

    eventually(Duration.ofSeconds(5), () -> Assert.assertEquals(1, signal.recorded().size()));

    publisher.close();
  }

  @Test
  public void testFlushTimeoutAfterMaxRetryDuration() throws InterruptedException {
    RecordingSignal signal = new RecordingSignal();
    signal.failure = new RuntimeException("boom");
    StreamPublisher publisher = newPublisher(signal, Duration.ofHours(1), 0, Duration.ofMillis(1));

    publisher.publish("t", "a", false);

    // The first flush sets pending and fails to send (transient "boom").
    try {
      publisher.flush();
      Assert.fail("unreachable");
    } catch (RuntimeException e) {
      Assert.assertEquals("boom", e.getMessage());
    }

    // Wait past the retry window with ample margin for coarse OS timer granularity. The
    // next flush sees the window exceeded and throws FlushTimeoutException.
    Thread.sleep(50);

    try {
      publisher.flush();
      Assert.fail("unreachable");
    } catch (FlushTimeoutException expected) {
    }

    publisher.close();
  }
}
