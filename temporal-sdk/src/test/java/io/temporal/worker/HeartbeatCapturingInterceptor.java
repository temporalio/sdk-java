package io.temporal.worker;

import io.grpc.*;
import io.temporal.api.workflowservice.v1.RecordWorkerHeartbeatRequest;
import io.temporal.api.workflowservice.v1.ShutdownWorkerRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * gRPC interceptor that captures heartbeat-related RPCs for test assertions. Captures the request
 * before the server responds, so tests work even if the test server returns UNIMPLEMENTED.
 */
class HeartbeatCapturingInterceptor implements ClientInterceptor {
  private final List<RecordWorkerHeartbeatRequest> heartbeatRequests =
      Collections.synchronizedList(new ArrayList<>());
  private final List<ShutdownWorkerRequest> shutdownRequests =
      Collections.synchronizedList(new ArrayList<>());

  @Override
  public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
      MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
    return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
        next.newCall(method, callOptions)) {
      @Override
      public void sendMessage(ReqT message) {
        if (message instanceof RecordWorkerHeartbeatRequest) {
          heartbeatRequests.add((RecordWorkerHeartbeatRequest) message);
        } else if (message instanceof ShutdownWorkerRequest) {
          shutdownRequests.add((ShutdownWorkerRequest) message);
        }
        super.sendMessage(message);
      }
    };
  }

  List<RecordWorkerHeartbeatRequest> getHeartbeatRequests() {
    return new ArrayList<>(heartbeatRequests);
  }

  List<ShutdownWorkerRequest> getShutdownRequests() {
    return new ArrayList<>(shutdownRequests);
  }

  void clear() {
    heartbeatRequests.clear();
    shutdownRequests.clear();
  }
}
