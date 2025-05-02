package io.temporal.internal.nexus;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.common.base.Strings;
import java.util.Base64;

public class OperationTokenUtil {
  private static final ObjectMapper mapper = new ObjectMapper().registerModule(new Jdk8Module());
  private static final ObjectWriter ow = mapper.writer();
  private static final Base64.Decoder decoder = Base64.getUrlDecoder();
  private static final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();

  /**
   * Load a workflow run operation token from an operation token.
   *
   * @throws IllegalArgumentException if the operation token is invalid
   */
  public static WorkflowRunOperationToken loadWorkflowRunOperationToken(String operationToken) {
    WorkflowRunOperationToken token;
    try {
      JavaType reference = mapper.getTypeFactory().constructType(WorkflowRunOperationToken.class);
      token = mapper.readValue(decoder.decode(operationToken), reference);
    } catch (Exception e) {
      throw new IllegalArgumentException("Failed to parse operation token: " + e.getMessage());
    }
    if (!token.getType().equals(OperationTokenType.WORKFLOW_RUN)) {
      throw new IllegalArgumentException(
          "Invalid workflow run token: incorrect operation token type: " + token.getType());
    }
    if (token.getVersion() != null) {
      throw new IllegalArgumentException("Invalid workflow run token: unexpected version field");
    }
    if (Strings.isNullOrEmpty(token.getWorkflowId())) {
      throw new IllegalArgumentException("Invalid workflow run token: missing workflow ID (wid)");
    }
    return token;
  }

  /**
   * Attempt to extract the workflow Id from an operation token.
   *
   * @throws IllegalArgumentException if the operation token is invalid
   */
  public static String loadWorkflowIdFromOperationToken(String operationToken) {
    return loadWorkflowRunOperationToken(operationToken).getWorkflowId();
  }

  /** Generate a workflow run operation token from a workflow ID and namespace. */
  public static String generateWorkflowRunOperationToken(String workflowId, String namespace)
      throws JsonProcessingException {
    String json = ow.writeValueAsString(new WorkflowRunOperationToken(namespace, workflowId));
    return encoder.encodeToString(json.getBytes());
  }

  private OperationTokenUtil() {}
}
