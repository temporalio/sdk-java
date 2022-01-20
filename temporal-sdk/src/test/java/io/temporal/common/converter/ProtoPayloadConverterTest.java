/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

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
    DataConverter converter = DataConverter.getDefaultInstance();
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
    DataConverter converter = DataConverter.getDefaultInstance();
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
