package io.temporal.common.converter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.api.common.v1.Payload;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.junit.After;
import org.junit.Test;
import tools.jackson.databind.json.JsonMapper;

public class Jackson3JsonPayloadConverterTest {

  @After
  public void resetJackson3Delegate() {
    JacksonJsonPayloadConverter.setDefaultAsJackson3(false, false);
  }

  @Test
  public void testSimple() {
    Jackson3JsonPayloadConverter converter = new Jackson3JsonPayloadConverter();
    TestPayload payload = new TestPayload(1L, Instant.now(), "myPayload");
    Optional<Payload> data = converter.toData(payload);
    assertTrue(data.isPresent());

    // Jackson 3 native defaults sort fields alphabetically (id, name, timestamp)
    // unlike jackson2Compat which preserves declaration order (id, timestamp, name)
    String json = data.get().getData().toStringUtf8();
    assertTrue(
        "Expected alphabetical field order (Jackson 3 native), got: " + json,
        json.indexOf("\"name\"") < json.indexOf("\"timestamp\""));

    TestPayload converted = converter.fromData(data.get(), TestPayload.class, TestPayload.class);
    assertEquals(payload, converted);
  }

  @Test
  public void testSimpleJackson2Compat() {
    Jackson3JsonPayloadConverter converter = new Jackson3JsonPayloadConverter(true);
    TestPayload payload = new TestPayload(1L, Instant.now(), "myPayload");
    Optional<Payload> data = converter.toData(payload);
    assertTrue(data.isPresent());

    // jackson2Compat preserves declaration order (id, timestamp, name)
    // unlike Jackson 3 native which sorts alphabetically (id, name, timestamp)
    String json = data.get().getData().toStringUtf8();
    assertTrue(
        "Expected declaration field order (jackson2Compat), got: " + json,
        json.indexOf("\"timestamp\"") < json.indexOf("\"name\""));

    TestPayload converted = converter.fromData(data.get(), TestPayload.class, TestPayload.class);
    assertEquals(payload, converted);
  }

  @Test
  public void testCustomJsonMapper() {
    JsonMapper mapper =
        Jackson3JsonPayloadConverter.newDefaultJsonMapper(false)
            .rebuild()
            .enable(tools.jackson.databind.SerializationFeature.INDENT_OUTPUT)
            .build();
    Jackson3JsonPayloadConverter converter = new Jackson3JsonPayloadConverter(mapper);
    TestPayload payload = new TestPayload(1L, Instant.now(), "test");
    Optional<Payload> data = converter.toData(payload);
    assertTrue(data.isPresent());
    String json = data.get().getData().toStringUtf8();
    assertTrue("Expected pretty-printed JSON", json.contains("\n"));
  }

  @Test
  public void testEncodingType() {
    Jackson3JsonPayloadConverter converter = new Jackson3JsonPayloadConverter();
    assertEquals("json/plain", converter.getEncodingType());
  }

  @Test
  public void testWireCompatibilityBetweenJackson2AndJackson3() {
    JacksonJsonPayloadConverter jackson2 = new JacksonJsonPayloadConverter();
    Jackson3JsonPayloadConverter jackson3 = new Jackson3JsonPayloadConverter(true);

    TestPayload payload = new TestPayload(42L, Instant.parse("2024-01-15T10:30:00Z"), "wireTest");

    // Jackson 2 serialized -> Jackson 3 deserialized
    Optional<Payload> data2 = jackson2.toData(payload);
    assertTrue(data2.isPresent());
    assertEquals(payload, jackson3.fromData(data2.get(), TestPayload.class, TestPayload.class));

    // Jackson 3 serialized -> Jackson 2 deserialized
    Optional<Payload> data3 = jackson3.toData(payload);
    assertTrue(data3.isPresent());
    assertEquals(payload, jackson2.fromData(data3.get(), TestPayload.class, TestPayload.class));
  }

  @Test
  public void testSetDefaultAsJackson3() {
    JacksonJsonPayloadConverter.setDefaultAsJackson3(true, false);

    Optional<Payload> data =
        GlobalDataConverter.get().toPayload(new TestPayload(1L, Instant.now(), "delegated"));
    assertTrue(data.isPresent());

    // Alphabetical field order proves Jackson 3 native is being used
    String json = data.get().getData().toStringUtf8();
    assertTrue(
        "Expected alphabetical field order (Jackson 3 native), got: " + json,
        json.indexOf("\"name\"") < json.indexOf("\"timestamp\""));
  }

  @Test
  public void testSetDefaultAsJackson3WithCompat() {
    JacksonJsonPayloadConverter.setDefaultAsJackson3(true, true);

    Optional<Payload> data =
        GlobalDataConverter.get().toPayload(new TestPayload(1L, Instant.now(), "delegated-compat"));
    assertTrue(data.isPresent());

    // Declaration field order proves Jackson 3 with jackson2Compat is being used
    String json = data.get().getData().toStringUtf8();
    assertTrue(
        "Expected declaration field order (jackson2Compat), got: " + json,
        json.indexOf("\"timestamp\"") < json.indexOf("\"name\""));
  }

  @Test
  public void testExplicitObjectMapperIgnoresJackson3Delegate() {
    // Enable Jackson 3 native globally (which sorts fields alphabetically)
    JacksonJsonPayloadConverter.setDefaultAsJackson3(true, false);

    // Converter created with explicit ObjectMapper should NOT delegate to Jackson 3
    ObjectMapper mapper = JacksonJsonPayloadConverter.newDefaultObjectMapper();
    JacksonJsonPayloadConverter converter = new JacksonJsonPayloadConverter(mapper);

    TestPayload payload = new TestPayload(1L, Instant.now(), "explicit");
    Optional<Payload> data = converter.toData(payload);
    assertTrue(data.isPresent());

    // Declaration field order proves Jackson 2 is still being used, not the Jackson 3 delegate
    String json = data.get().getData().toStringUtf8();
    assertTrue(
        "Expected declaration field order (Jackson 2), got: " + json,
        json.indexOf("\"timestamp\"") < json.indexOf("\"name\""));
  }

  static class TestPayload {
    private long id;
    private Instant timestamp;
    private String name;

    public TestPayload() {}

    TestPayload(long id, Instant timestamp, String name) {
      this.id = id;
      this.timestamp = timestamp;
      this.name = name;
    }

    public long getId() {
      return id;
    }

    public void setId(long id) {
      this.id = id;
    }

    public Instant getTimestamp() {
      return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
      this.timestamp = timestamp;
    }

    public String getName() {
      return name;
    }

    public void setName(String name) {
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
      TestPayload that = (TestPayload) o;
      return id == that.id
          && Objects.equals(timestamp, that.timestamp)
          && Objects.equals(name, that.name);
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
