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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.protobuf.util.JsonFormat;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.WorkflowExecution;
import java.time.Instant;
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
  public void testCustomJson() {
    ObjectMapper objectMapper =
        new ObjectMapper()
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, true)
            .registerModule(new JavaTimeModule());
    DataConverter converter =
        new DefaultDataConverter(true, new JacksonJsonPayloadConverter(objectMapper));
    TestPayload payload = new TestPayload(1L, Instant.now(), "myPayload");
    Optional<Payloads> data = converter.toPayloads(payload);
    TestPayload converted = converter.fromPayloads(0, data, TestPayload.class, TestPayload.class);
    assertEquals(payload, converted);
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
        new DefaultDataConverter(
            true,
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
