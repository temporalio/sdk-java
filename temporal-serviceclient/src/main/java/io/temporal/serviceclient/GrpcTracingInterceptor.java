package io.temporal.serviceclient;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class GrpcTracingInterceptor implements ClientInterceptor {

  private static final Logger log = LoggerFactory.getLogger(GrpcTracingInterceptor.class);

  /**
   * Separate logger for PollWorkflowTaskQueue reply which includes history. It is separate to allow
   * disabling this noisy log independently through configuration.
   */
  private static final Logger workflow_task_log =
      LoggerFactory.getLogger(GrpcTracingInterceptor.class.getName() + ":history");

  @Override
  public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
      MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
    return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
        next.newCall(method, callOptions)) {
      @Override
      public void sendMessage(ReqT message) {
        log.trace("Invoking \"" + method.getFullMethodName() + "\" with input: " + message);
        super.sendMessage(message);
      }

      @Override
      public void start(Listener<RespT> responseListener, Metadata headers) {
        Listener<RespT> listener =
            new ForwardingClientCallListener.SimpleForwardingClientCallListener<RespT>(
                responseListener) {
              @Override
              public void onMessage(RespT message) {
                // Skip printing the whole history
                if (method == WorkflowServiceGrpc.getPollWorkflowTaskQueueMethod()) {
                  if (workflow_task_log.isTraceEnabled()) {
                    workflow_task_log.trace(
                        "Returned \""
                            + method.getServiceName()
                            + "\" of \""
                            + method.getFullMethodName()
                            + "\" with output: "
                            + message);
                  } else if (log.isTraceEnabled()) {
                    log.trace("Returned " + method.getFullMethodName());
                  }
                } else if (log.isTraceEnabled()) {
                  log.trace(
                      "Returned \""
                          + method.getServiceName()
                          + "\" of \""
                          + method.getFullMethodName()
                          + "\" with output: "
                          + message);
                }
                super.onMessage(message);
              }
            };
        super.start(listener, headers);
      }
    };
  }

  public static boolean isEnabled() {
    return log.isTraceEnabled() || workflow_task_log.isTraceEnabled();
  }
}
