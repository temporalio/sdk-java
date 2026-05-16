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
   * Load and validate an operation token without asserting the token type. Use this for cancel
   * dispatch where the token type determines the cancel behavior.
   *
   * @throws IllegalArgumentException if the operation token is malformed or has invalid structure
   */
  public static OperationToken loadOperationToken(String operationToken) {
    OperationToken token;
    try {
      JavaType reference = mapper.getTypeFactory().constructType(OperationToken.class);
      token = mapper.readValue(decoder.decode(operationToken), reference);
    } catch (Exception e) {
      throw new IllegalArgumentException("Failed to parse operation token: " + e.getMessage());
    }
    if (token.getVersion() != null && token.getVersion() != 0) {
      throw new IllegalArgumentException("Invalid operation token: unexpected version field");
    }
    switch (token.getType()) {
      case WORKFLOW_RUN:
        if (Strings.isNullOrEmpty(token.getWorkflowId())) {
          throw new IllegalArgumentException("Invalid operation token: missing workflow ID (wid)");
        }
        break;
      case ACTIVITY_EXECUTION:
        if (Strings.isNullOrEmpty(token.getActivityId())) {
          throw new IllegalArgumentException("Invalid operation token: missing activity ID (aid)");
        }
        break;
      default:
        throw new IllegalArgumentException(
            "Invalid operation token: unknown operation token type: " + token.getType());
    }
    return token;
  }

  /**
   * Load a workflow run operation token, asserting that the token type is {@link
   * OperationTokenType#WORKFLOW_RUN}.
   *
   * @throws IllegalArgumentException if the operation token is invalid or not a workflow run token
   */
  public static OperationToken loadWorkflowRunOperationToken(String operationToken) {
    OperationToken token = loadOperationToken(operationToken);
    if (!token.getType().equals(OperationTokenType.WORKFLOW_RUN)) {
      throw new IllegalArgumentException(
          "Invalid workflow run token: incorrect operation token type: " + token.getType());
    }
    return token;
  }

  /**
   * Extract the workflow ID from a workflow run operation token.
   *
   * @throws IllegalArgumentException if the operation token is invalid
   */
  public static String loadWorkflowIdFromOperationToken(String operationToken) {
    return loadWorkflowRunOperationToken(operationToken).getWorkflowId();
  }

  /**
   * Load an activity execution operation token, asserting that the token type is {@link
   * OperationTokenType#ACTIVITY_EXECUTION}.
   *
   * @throws IllegalArgumentException if the operation token is invalid or not an activity execution
   *     token
   */
  public static OperationToken loadActivityExecutionOperationToken(String operationToken) {
    OperationToken token = loadOperationToken(operationToken);
    if (!token.getType().equals(OperationTokenType.ACTIVITY_EXECUTION)) {
      throw new IllegalArgumentException(
          "Invalid activity execution token: incorrect operation token type: " + token.getType());
    }
    return token;
  }

  /**
   * Extract the activity ID from an activity execution operation token.
   *
   * @throws IllegalArgumentException if the operation token is invalid
   */
  public static String loadActivityIdFromOperationToken(String operationToken) {
    return loadActivityExecutionOperationToken(operationToken).getActivityId();
  }

  /** Generate a workflow run operation token from a workflow ID and namespace. */
  public static String generateWorkflowRunOperationToken(String workflowId, String namespace)
      throws JsonProcessingException {
    String json =
        ow.writeValueAsString(
            new OperationToken(OperationTokenType.WORKFLOW_RUN, namespace, workflowId));
    return encoder.encodeToString(json.getBytes());
  }

  /**
   * Generate an activity execution operation token from an activity ID and namespace.
   *
   * <p>This overload omits the run ID. Use it when writing the token into the Nexus operation-token
   * callback header — that token is generated before the start RPC completes, so the run ID is not
   * yet known.
   */
  public static String generateActivityExecutionOperationToken(String activityId, String namespace)
      throws JsonProcessingException {
    return generateActivityExecutionOperationToken(activityId, null, namespace);
  }

  /**
   * Generate an activity execution operation token from an activity ID, run ID, and namespace. The
   * {@code runId} is included only when non-null.
   *
   * <p>This overload is used for the operation token returned to the Nexus caller from a start
   * operation — at that point the start RPC has completed and the run ID is known. The header token
   * written into the activity completion callback must NOT carry a run ID; use {@link
   * #generateActivityExecutionOperationToken(String, String)} for that path.
   */
  public static String generateActivityExecutionOperationToken(
      String activityId, String runId, String namespace) throws JsonProcessingException {
    String json =
        ow.writeValueAsString(
            new OperationToken(
                OperationTokenType.ACTIVITY_EXECUTION, namespace, null, activityId, runId));
    return encoder.encodeToString(json.getBytes());
  }

  private OperationTokenUtil() {}
}
