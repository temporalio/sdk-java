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
import io.temporal.api.common.v1.WorkflowExecution;
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
}
