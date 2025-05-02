package io.temporal.internal.testservice;

import io.grpc.StatusRuntimeException;
import io.temporal.api.command.v1.Command;
import io.temporal.api.enums.v1.WorkflowTaskFailedCause;
import io.temporal.failure.ServerFailure;
import io.temporal.internal.common.ProtoEnumNameUtils;

class CommandVerifier {
  private final TestVisibilityStore visibilityStore;
  private final TestNexusEndpointStore nexusEndpointStore;

  public CommandVerifier(
      TestVisibilityStore visibilityStore, TestNexusEndpointStore nexusEndpointStore) {
    this.visibilityStore = visibilityStore;
    this.nexusEndpointStore = nexusEndpointStore;
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
        break;
      case COMMAND_TYPE_SCHEDULE_NEXUS_OPERATION:
        try {
          nexusEndpointStore.getEndpointByName(
              d.getScheduleNexusOperationCommandAttributes().getEndpoint());
        } catch (StatusRuntimeException e) {
          ServerFailure eventAttributesFailure =
              new ServerFailure(
                  ProtoEnumNameUtils.uniqueToSimplifiedName(
                          WorkflowTaskFailedCause
                              .WORKFLOW_TASK_FAILED_CAUSE_BAD_SCHEDULE_NEXUS_OPERATION_ATTRIBUTES)
                      + ": "
                      + e.getStatus().getDescription(),
                  true);
          return new InvalidCommandResult(
              WorkflowTaskFailedCause
                  .WORKFLOW_TASK_FAILED_CAUSE_BAD_SCHEDULE_NEXUS_OPERATION_ATTRIBUTES,
              eventAttributesFailure,
              e);
        }
        break;
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
