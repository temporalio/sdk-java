package io.temporal.nexus;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.internal.client.NexusStartWorkflowRequest;

interface WorkflowHandleInvoker {
  WorkflowExecution invoke(NexusStartWorkflowRequest request);
}
