package io.temporal.common.converter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.temporal.api.common.v1.Payloads;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.junit.After;
import org.junit.Test;

public class JacksonJsonPayloadConverterTest {

  @After
  public void resetJackson3Delegate() {
    JacksonJsonPayloadConverter.setDefaultAsJackson3(false, false);
  }

  @Test
  public void testSetDefaultAsJackson3ThrowsWithoutJackson3() {
    try {
      JacksonJsonPayloadConverter.setDefaultAsJackson3(true, true);
      fail("Expected IllegalStateException");
    } catch (IllegalStateException e) {
      // Both the Java 8 stub and the Java 17 class reference Jackson 3 types, so reflective
      // constructor lookup fails while linking when Jackson 3 is absent.
      Throwable cause = e.getCause();
      assertTrue(
          "Expected NoClassDefFoundError, got: " + cause, cause instanceof NoClassDefFoundError);
    }
  }

  @Test
  public void testSetDefaultAsJackson3FalseIsNoOp() {
    // Should not throw even though Jackson 3 is absent
    JacksonJsonPayloadConverter.setDefaultAsJackson3(false, false);
    JacksonJsonPayloadConverter.setDefaultAsJackson3(false, true);
  }

  @Test
  public void testJson() {
    DataConverter converter = DefaultDataConverter.newDefaultInstance();
    ProtoPayloadConverterTest.TestPayload payload =
        new ProtoPayloadConverterTest.TestPayload(1L, Instant.now(), "myPayload");
    Optional<Payloads> data = converter.toPayloads(payload);
    ProtoPayloadConverterTest.TestPayload converted =
        converter.fromPayloads(
            0,
            data,
            ProtoPayloadConverterTest.TestPayload.class,
            ProtoPayloadConverterTest.TestPayload.class);
    assertEquals(payload, converted);
  }

  @Test
  public void testJsonWithOptional() {
    DataConverter converter = DefaultDataConverter.newDefaultInstance();
    TestOptionalPayload payload =
        new TestOptionalPayload(
            Optional.of(1L), Optional.of(Instant.now()), Optional.of("myPayload"));
    Optional<Payloads> data = converter.toPayloads(payload);
    TestOptionalPayload converted =
        converter.fromPayloads(0, data, TestOptionalPayload.class, TestOptionalPayload.class);
    assertEquals(payload, converted);

    assertEquals(Long.valueOf(1L), converted.getId().get());
    assertEquals("myPayload", converted.getName().get());
  }

  static class TestOptionalPayload {
    private Optional<Long> id;
    private Optional<Instant> timestamp;
    private Optional<String> name;

    public TestOptionalPayload() {}

    TestOptionalPayload(Optional<Long> id, Optional<Instant> timestamp, Optional<String> name) {
      this.id = id;
      this.timestamp = timestamp;
      this.name = name;
    }

    public Optional<Long> getId() {
      return id;
    }

    public void setId(Optional<Long> id) {
      this.id = id;
    }

    public Optional<Instant> getTimestamp() {
      return timestamp;
    }

    public void setTimestamp(Optional<Instant> timestamp) {
      this.timestamp = timestamp;
    }

    public Optional<String> getName() {
      return name;
    }

    public void setName(Optional<String> name) {
      this.name = name;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      TestOptionalPayload that = (TestOptionalPayload) o;
      return getId().get().equals(that.getId().get())
          && Objects.equals(getTimestamp().get(), that.getTimestamp().get())
          && Objects.equals(getName().get(), that.getName().get());
    }

    @Override
    public int hashCode() {
      return Objects.hash(id, timestamp, name);
    }

    @Override
    public String toString() {
      return "TestPayload{"
          + "id="
          + id
          + ", timestamp="
          + timestamp
          + ", name='"
          + name
          + '\''
          + '}';
    }
  }
}
