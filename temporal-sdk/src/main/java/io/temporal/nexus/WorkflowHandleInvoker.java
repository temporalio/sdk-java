package io.temporal.nexus;

import io.temporal.internal.client.NexusStartWorkflowRequest;
import io.temporal.internal.client.NexusStartWorkflowResponse;

interface WorkflowHandleInvoker {
  NexusStartWorkflowResponse invoke(NexusStartWorkflowRequest request);
}
