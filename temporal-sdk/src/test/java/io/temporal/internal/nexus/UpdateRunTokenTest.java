package io.temporal.internal.nexus;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import java.util.Base64;
import org.junit.Assert;
import org.junit.Test;

public class UpdateRunTokenTest {
  private static final ObjectWriter ow =
      new ObjectMapper().registerModule(new Jdk8Module()).writer();
  private static final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();

  private static final String namespace = "ns";
  private static final String workflowId = "w";
  private static final String runId = "r";
  private static final String updateId = "u";

  @Test
  public void serializeWorkflowUpdateTokenOmitsEmptyRunId() throws JsonProcessingException {
    OperationToken token = new OperationToken("ns", "wid", null, "uid");
    String json = ow.writeValueAsString(token);
    JsonNode node = new ObjectMapper().readTree(json);
    Assert.assertFalse(node.has("rid"));
  }

  @Test
  public void testEncodeDecode() throws JsonProcessingException {
    String encoded =
        OperationTokenUtil.generateWorkflowUpdateOperationToken(
            namespace, workflowId, runId, updateId);

    OperationToken token = OperationTokenUtil.loadWorkflowUpdateOperationToken(encoded);
    Assert.assertEquals(OperationTokenType.WORKFLOW_UPDATE, token.getType());
    Assert.assertEquals(namespace, token.getNamespace());
    Assert.assertEquals(workflowId, token.getWorkflowId());
    Assert.assertEquals(runId, token.getRunId());
    Assert.assertEquals(updateId, token.getUpdateId());
    Assert.assertNull(token.getVersion());
  }

  @Test
  public void generateUpdateTokenRejectInvalid() {
    // missing ns
    Assert.assertThrows(
        IllegalArgumentException.class,
        () -> OperationTokenUtil.generateWorkflowUpdateOperationToken("", "w", "", "u"));
    // missing workflowId
    Assert.assertThrows(
        IllegalArgumentException.class,
        () -> OperationTokenUtil.generateWorkflowUpdateOperationToken("n", "", "", "u"));
    // missing updateId
    Assert.assertThrows(
        IllegalArgumentException.class,
        () -> OperationTokenUtil.generateWorkflowUpdateOperationToken("n", "w", "", ""));
  }

  @Test
  public void loadUpdateTokenRejectInvalid() {
    // missing namespace
    Assert.assertThrows(
        IllegalArgumentException.class,
        () ->
            OperationTokenUtil.loadWorkflowUpdateOperationToken(
                encoder.encodeToString(
                    "{\"t\":3,\"ns\":\"\",\"wid\":\"w\",\"uid\":\"u\"}".getBytes())));
    // missing workflowId
    Assert.assertThrows(
        IllegalArgumentException.class,
        () ->
            OperationTokenUtil.loadWorkflowUpdateOperationToken(
                encoder.encodeToString(
                    "{\"t\":3,\"ns\":\"n\",\"wid\":\"\",\"uid\":\"u\"}".getBytes())));
    // missing updateId
    Assert.assertThrows(
        IllegalArgumentException.class,
        () ->
            OperationTokenUtil.loadWorkflowUpdateOperationToken(
                encoder.encodeToString(
                    "{\"t\":3,\"ns\":\"n\",\"wid\":\"w\",\"uid\":\"\"}".getBytes())));
  }

  @Test
  public void rejectInvalidUpdateTokenLoads() throws JsonProcessingException {
    // loading update token from an encoded workflow run token should fail
    Assert.assertThrows(
        IllegalArgumentException.class,
        () ->
            OperationTokenUtil.loadWorkflowUpdateOperationToken(
                OperationTokenUtil.generateWorkflowRunOperationToken("w", "n")));
  }

  @Test
  public void loadWorkflowUpdateOperationTokenFromEncodedToken() {
    // {"t":3,"ns":"ns","wid":"w","rid":"r","uid":"u"}
    String encodedToken = "eyJ0IjozLCJucyI6Im5zIiwid2lkIjoidyIsInJpZCI6InIiLCJ1aWQiOiJ1In0";

    OperationToken token = OperationTokenUtil.loadWorkflowUpdateOperationToken(encodedToken);
    Assert.assertEquals(OperationTokenType.WORKFLOW_UPDATE, token.getType());
    Assert.assertEquals("ns", token.getNamespace());
    Assert.assertEquals("w", token.getWorkflowId());
    Assert.assertEquals("r", token.getRunId());
    Assert.assertEquals("u", token.getUpdateId());
  }
}
