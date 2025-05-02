package io.temporal.internal.nexus;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import java.io.IOException;
import java.util.Base64;
import org.junit.Assert;
import org.junit.Test;

public class WorkflowRunTokenTest {
  private static final ObjectWriter ow =
      new ObjectMapper().registerModule(new Jdk8Module()).writer();
  private static final ObjectReader or =
      new ObjectMapper().registerModule(new Jdk8Module()).reader();
  private static final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();

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

  @Test
  public void failLoadOldWorkflowRunToken() {
    String operationToken = "AAAAA-BBBBB-CCCCC";
    Assert.assertThrows(
        IllegalArgumentException.class,
        () -> OperationTokenUtil.loadWorkflowIdFromOperationToken(operationToken));
  }

  @Test
  public void loadWorkflowIdFromOperationToken() {
    String json = "{\"t\":1,\"ns\":\"namespace\",\"wid\":\"workflowId\"}";

    WorkflowRunOperationToken token =
        OperationTokenUtil.loadWorkflowRunOperationToken(encoder.encodeToString(json.getBytes()));
    Assert.assertEquals("workflowId", token.getWorkflowId());
    Assert.assertEquals("namespace", token.getNamespace());
    Assert.assertEquals(null, token.getVersion());
    Assert.assertEquals(OperationTokenType.WORKFLOW_RUN, token.getType());

    Assert.assertEquals(
        "workflowId",
        OperationTokenUtil.loadWorkflowIdFromOperationToken(
            encoder.encodeToString(json.getBytes())));
  }

  @Test
  public void loadWorkflowIdFromBadOperationToken() {
    // Bad token, empty json
    String badTokenEmptyJson = "{}";
    Assert.assertThrows(
        IllegalArgumentException.class,
        () ->
            OperationTokenUtil.loadWorkflowRunOperationToken(
                encoder.encodeToString(badTokenEmptyJson.getBytes())));

    // Bad token, missing the "wid" field
    String badTokenMissingWorkflow = "{\"t\":1,\"ns\":\"namespace\"}";
    Assert.assertThrows(
        IllegalArgumentException.class,
        () ->
            OperationTokenUtil.loadWorkflowRunOperationToken(
                encoder.encodeToString(badTokenMissingWorkflow.getBytes())));

    // Bad token, unknown version
    String badTokenUnknownVersion =
        "{\"t\":1,\"ns\":\"namespace\", \"wid\":\"workflowId\", \"v\":1}";
    Assert.assertThrows(
        IllegalArgumentException.class,
        () ->
            OperationTokenUtil.loadWorkflowRunOperationToken(
                encoder.encodeToString(badTokenUnknownVersion.getBytes())));

    // Bad token, unknown version
    String badTokenUnknownType = "{\"t\":4,\"ns\":\"namespace\", \"wid\":\"workflowId\", \"v\":1}";
    Assert.assertThrows(
        IllegalArgumentException.class,
        () ->
            OperationTokenUtil.loadWorkflowRunOperationToken(
                encoder.encodeToString(badTokenUnknownType.getBytes())));
  }
}
