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

import io.temporal.api.common.v1.Payloads;
import java.time.Instant;
import java.util.Optional;
import org.junit.Test;

public class JacksonJsonPayloadConverterTest {
  @Test
  public void testCustomJson() {
    DataConverter converter =
        DefaultDataConverter.newDefaultInstance()
            .withPayloadConverterOverrides(new JacksonJsonPayloadConverter());
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
  public void testCustomJsonWithOptional() {
    DataConverter converter =
        DefaultDataConverter.newDefaultInstance()
            .withPayloadConverterOverrides(new JacksonJsonPayloadConverter());
    ProtoPayloadConverterTest.TestOptionalPayload payload =
        new ProtoPayloadConverterTest.TestOptionalPayload(
            Optional.of(1L), Optional.of(Instant.now()), Optional.of("myPayload"));
    Optional<Payloads> data = converter.toPayloads(payload);
    ProtoPayloadConverterTest.TestOptionalPayload converted =
        converter.fromPayloads(
            0,
            data,
            ProtoPayloadConverterTest.TestOptionalPayload.class,
            ProtoPayloadConverterTest.TestOptionalPayload.class);
    assertEquals(payload, converted);

    assertEquals(Long.valueOf(1L), converted.getId().get());
    assertEquals("myPayload", converted.getName().get());
  }
}
