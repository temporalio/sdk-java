/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.temporal.internal.nexus;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import java.util.Collections;
import java.util.Map;
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
    payloadSerializer.deserialize(content, String.class);
    Assert.assertEquals(null, payloadSerializer.deserialize(content, String.class));
  }

  @Test
  public void testInteger() {
    PayloadSerializer.Content content = payloadSerializer.serialize(1);
    payloadSerializer.deserialize(content, Integer.class);
    Assert.assertEquals(1, payloadSerializer.deserialize(content, Integer.class));
  }

  @Test
  public void testHashMap() {
    Map<String, Integer> map = Collections.singletonMap("key", 0);
    PayloadSerializer.Content content = payloadSerializer.serialize(map);
    payloadSerializer.deserialize(content, map.getClass());
    Assert.assertEquals(map, payloadSerializer.deserialize(content, map.getClass()));
  }

  @Test
  public void testProto() {
    WorkflowExecution exec =
        WorkflowExecution.newBuilder().setWorkflowId("id").setRunId("runId").build();
    PayloadSerializer.Content content = payloadSerializer.serialize(exec);
    payloadSerializer.deserialize(content, WorkflowExecution.class);
    Assert.assertEquals(exec, payloadSerializer.deserialize(content, WorkflowExecution.class));
  }
}
