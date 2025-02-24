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
   * @throws FallbackToWorkflowIdException if the operation token is not a workflow run token
   * @throws IllegalArgumentException if the operation token is invalid
   */
  public static WorkflowRunOperationToken loadWorkflowRunOperationToken(String operationToken)
      throws FallbackToWorkflowIdException {
    WorkflowRunOperationToken token;
    try {
      JavaType reference = mapper.getTypeFactory().constructType(WorkflowRunOperationToken.class);
      token = mapper.readValue(decoder.decode(operationToken), reference);
    } catch (Exception e) {
      throw new FallbackToWorkflowIdException("Failed to parse operation token: " + e.getMessage());
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
    try {
      WorkflowRunOperationToken token = loadWorkflowRunOperationToken(operationToken);
      return token.getWorkflowId();
    } catch (OperationTokenUtil.FallbackToWorkflowIdException e) {
      // Previous versions of the SDK simply used the workflow ID as the operation token
      // This fallback is provided for backwards compatibility for those cases.
      // This fallback will be removed in a future release.
      // See: https://github.com/temporalio/sdk-java/issues/2423
      return operationToken;
    }
  }

  /** Generate a workflow run operation token from a workflow ID and namespace. */
  public static String generateWorkflowRunOperationToken(String workflowId, String namespace)
      throws JsonProcessingException {
    String json = ow.writeValueAsString(new WorkflowRunOperationToken(namespace, workflowId));
    return encoder.encodeToString(json.getBytes());
  }

  public static class FallbackToWorkflowIdException extends RuntimeException {
    public FallbackToWorkflowIdException(String message) {
      super(message);
    }
  }

  private OperationTokenUtil() {}
}
