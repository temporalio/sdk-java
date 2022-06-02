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

package io.temporal.internal.testservice;

import io.grpc.StatusRuntimeException;
import io.temporal.api.command.v1.Command;
import io.temporal.api.enums.v1.WorkflowTaskFailedCause;
import io.temporal.failure.ServerFailure;
import io.temporal.internal.common.ProtoEnumNameUtils;

class CommandVerifier {
  private final TestVisibilityStore visibilityStore;

  public CommandVerifier(TestVisibilityStore visibilityStore) {
    this.visibilityStore = visibilityStore;
  }

  InvalidCommandResult verifyCommand(RequestContext ctx, Command d) {
    switch (d.getCommandType()) {
      case COMMAND_TYPE_UPSERT_WORKFLOW_SEARCH_ATTRIBUTES:
        try {
          visibilityStore.validateSearchAttributes(
              d.getUpsertWorkflowSearchAttributesCommandAttributes().getSearchAttributes());
        } catch (StatusRuntimeException e) {
          ServerFailure eventAttributesFailure =
              new ServerFailure(
                  ProtoEnumNameUtils.uniqueToSimplifiedName(
                          WorkflowTaskFailedCause.WORKFLOW_TASK_FAILED_CAUSE_BAD_SEARCH_ATTRIBUTES)
                      + ": "
                      + e.getStatus().getDescription(),
                  true);
          return new InvalidCommandResult(
              WorkflowTaskFailedCause.WORKFLOW_TASK_FAILED_CAUSE_BAD_SEARCH_ATTRIBUTES,
              eventAttributesFailure,
              e);
        }
    }
    return null;
  }

  static class InvalidCommandResult {
    private final WorkflowTaskFailedCause workflowTaskFailedCause;
    private final ServerFailure eventAttributesFailure;
    private final RuntimeException clientException;

    public InvalidCommandResult(
        WorkflowTaskFailedCause workflowTaskFailedCause,
        ServerFailure eventAttributesFailure,
        RuntimeException clientException) {
      this.workflowTaskFailedCause = workflowTaskFailedCause;
      this.eventAttributesFailure = eventAttributesFailure;
      this.clientException = clientException;
    }

    public WorkflowTaskFailedCause getWorkflowTaskFailedCause() {
      return workflowTaskFailedCause;
    }

    /**
     * @return an exception to be used for a failure in the event attributes.
     */
    public ServerFailure getEventAttributesFailure() {
      return eventAttributesFailure;
    }

    /**
     * @return an exception to be returned to the client
     */
    public RuntimeException getClientException() {
      return clientException;
    }
  }
}
