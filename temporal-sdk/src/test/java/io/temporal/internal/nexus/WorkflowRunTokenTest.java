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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import java.io.IOException;
import org.junit.Assert;
import org.junit.Test;

public class WorkflowRunTokenTest {
  private static final ObjectWriter ow =
      new ObjectMapper().registerModule(new Jdk8Module()).writer();
  private static final ObjectReader or =
      new ObjectMapper().registerModule(new Jdk8Module()).reader();

  @Test
  public void serializeWorkflowRunToken() throws JsonProcessingException {
    WorkflowRunOperationToken token = new WorkflowRunOperationToken("namespace", "workflowId");
    String json = ow.writeValueAsString(token);
    final JsonNode node = new ObjectMapper().readTree(json);
    System.out.println(json);
    // Assert that the serialized JSON is as expected
    Assert.assertEquals(1, node.get("t").asInt());
    Assert.assertEquals("namespace", node.get("ns").asText());
    Assert.assertEquals("workflowId", node.get("wid").asText());
    // Version field should not be serialized as it is null
    Assert.assertFalse(node.has("v"));
  }

  @Test
  public void deserializeWorkflowRunTokenWithVersion() throws IOException {
    String json = "{\"t\":1,\"ns\":\"namespace\",\"wid\":\"workflowId\",\"v\":1}";
    JavaType reference =
        new ObjectMapper().getTypeFactory().constructType(WorkflowRunOperationToken.class);
    WorkflowRunOperationToken token = new ObjectMapper().readValue(json.getBytes(), reference);
    // Assert that the serialized JSON is as expected
    Assert.assertEquals(OperationTokenType.WORKFLOW_RUN, token.getType());
    Assert.assertEquals(new Integer(1), token.getVersion());
    Assert.assertEquals("namespace", token.getNamespace());
    Assert.assertEquals("workflowId", token.getWorkflowId());
  }

  @Test
  public void deserializeWorkflowRunToken() throws IOException {
    String json = "{\"t\":1,\"ns\":\"namespace\",\"wid\":\"workflowId\"}";
    JavaType reference =
        new ObjectMapper().getTypeFactory().constructType(WorkflowRunOperationToken.class);
    WorkflowRunOperationToken token = new ObjectMapper().readValue(json.getBytes(), reference);
    // Assert that the serialized JSON is as expected
    Assert.assertEquals(OperationTokenType.WORKFLOW_RUN, token.getType());
    Assert.assertNull(null, token.getVersion());
    Assert.assertEquals("namespace", token.getNamespace());
    Assert.assertEquals("workflowId", token.getWorkflowId());
  }
}
