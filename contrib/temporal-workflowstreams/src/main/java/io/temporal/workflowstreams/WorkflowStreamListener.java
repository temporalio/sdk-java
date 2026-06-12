package io.temporal.workflowstreams;

import io.temporal.common.Experimental;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.UpdateValidatorMethod;

/**
 * The signal, update, and query handlers a {@link WorkflowStream} registers on its workflow via
 * {@link io.temporal.workflow.Workflow#registerListener(Object)}. The handler names are part of the
 * cross-language wire protocol.
 *
 * <p>This interface is public only because listener methods are invoked reflectively; user code
 * should not implement it. Construct a {@link WorkflowStream} instead.
 */
@Experimental
public interface WorkflowStreamListener {
  @SignalMethod(name = WorkflowStreamConstants.PUBLISH_SIGNAL_NAME)
  void publish(PublishInput input);

  @UpdateMethod(name = WorkflowStreamConstants.POLL_UPDATE_NAME)
  PollResult poll(PollInput input);

  @UpdateValidatorMethod(updateName = WorkflowStreamConstants.POLL_UPDATE_NAME)
  void validatePoll(PollInput input);

  @QueryMethod(name = WorkflowStreamConstants.OFFSET_QUERY_NAME)
  long offset();
}
