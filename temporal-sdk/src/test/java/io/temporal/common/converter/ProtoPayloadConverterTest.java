package io.temporal.common.converter;

import static org.junit.Assert.assertEquals;

import com.google.protobuf.ByteString;
import com.google.protobuf.MapEntry;
import com.google.protobuf.util.JsonFormat;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.WorkflowExecution;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.junit.Test;

public class ProtoPayloadConverterTest {

  @Test
  public void testProtoJson() {
    DataConverter converter = DefaultDataConverter.STANDARD_INSTANCE;
    WorkflowExecution execution =
        WorkflowExecution.newBuilder()
            .setWorkflowId(UUID.randomUUID().toString())
            .setRunId(UUID.randomUUID().toString())
            .build();
    Optional<Payloads> data = converter.toPayloads(execution);
    WorkflowExecution converted =
        converter.fromPayloads(0, data, WorkflowExecution.class, WorkflowExecution.class);
    assertEquals(execution, converted);
  }

  @Test
  public void testProto() {
    DataConverter converter = new DefaultDataConverter(new ProtobufPayloadConverter());
    WorkflowExecution execution =
        WorkflowExecution.newBuilder()
            .setWorkflowId(UUID.randomUUID().toString())
            .setRunId(UUID.randomUUID().toString())
            .build();
    Optional<Payloads> data = converter.toPayloads(execution);
    WorkflowExecution converted =
        converter.fromPayloads(0, data, WorkflowExecution.class, WorkflowExecution.class);
    assertEquals(execution, converted);
  }

  @Test
  public void testRawValue() {
    DataConverter converter = DefaultDataConverter.STANDARD_INSTANCE;
    ProtobufPayloadConverter protoConverter = new ProtobufPayloadConverter();
    WorkflowExecution execution =
        WorkflowExecution.newBuilder()
            .setWorkflowId(UUID.randomUUID().toString())
            .setRunId(UUID.randomUUID().toString())
            .build();
    Optional<Payloads> data =
        converter.toPayloads(new RawValue(protoConverter.toData(execution).get()));
    WorkflowExecution converted =
        converter.fromPayloads(0, data, WorkflowExecution.class, WorkflowExecution.class);
    assertEquals(execution, converted);
  }

  @Test
  public void testRawValuePassThrough() {
    DataConverter converter = DefaultDataConverter.STANDARD_INSTANCE;
    Payload p = Payload.newBuilder().setData(ByteString.copyFromUtf8("test")).build();
    Optional<Payloads> data = converter.toPayloads(new RawValue(p));
    RawValue converted = converter.fromPayloads(0, data, RawValue.class, RawValue.class);
    assertEquals(p, converted.getPayload());
  }

  @Test
  public void testCustomProto() {
    DataConverter converter =
        DefaultDataConverter.newDefaultInstance()
            .withPayloadConverterOverrides(
                new ProtobufJsonPayloadConverter(
                    JsonFormat.printer().printingEnumsAsInts(), JsonFormat.parser()));
    WorkflowExecution execution =
        WorkflowExecution.newBuilder()
            .setWorkflowId(UUID.randomUUID().toString())
            .setRunId(UUID.randomUUID().toString())
            .build();
    Optional<Payloads> data = converter.toPayloads(execution);
    WorkflowExecution converted =
        converter.fromPayloads(0, data, WorkflowExecution.class, WorkflowExecution.class);
    assertEquals(execution, converted);
  }

  @Test
  public void testProtoMessageType() {
    DataConverter converter = DefaultDataConverter.STANDARD_INSTANCE;
    WorkflowExecution execution =
        WorkflowExecution.newBuilder()
            .setWorkflowId(UUID.randomUUID().toString())
            .setRunId(UUID.randomUUID().toString())
            .build();
    Optional<Payloads> data = converter.toPayloads(execution);
    Payloads payloads = data.get();
    Object field = payloads.getField(payloads.getDescriptorForType().findFieldByName("payloads"));
    Payload payload = (Payload) ((List<?>) field).get(0);
    Object metadata = payload.getField(payload.getDescriptorForType().findFieldByName("metadata"));
    MapEntry<?, ?> secondMetadata = (MapEntry<?, ?>) ((List<?>) metadata).get(1);
    assertEquals("messageType", secondMetadata.getKey());
    assertEquals(
        "temporal.api.common.v1.WorkflowExecution",
        ((ByteString) secondMetadata.getValue()).toString(StandardCharsets.UTF_8));
  }

  @Test
  public void testProtoMessageTypeExclusion() {
    DataConverter converter = new DefaultDataConverter(new ProtobufPayloadConverter(true));
    WorkflowExecution execution =
        WorkflowExecution.newBuilder()
            .setWorkflowId(UUID.randomUUID().toString())
            .setRunId(UUID.randomUUID().toString())
            .build();
    Optional<Payloads> data = converter.toPayloads(execution);
    Payloads payloads = data.get();
    Object field = payloads.getField(payloads.getDescriptorForType().findFieldByName("payloads"));
    Payload payload = (Payload) ((List<?>) field).get(0);
    Object metadata = payload.getField(payload.getDescriptorForType().findFieldByName("metadata"));
    assertEquals(1, ((List<?>) metadata).size());
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
