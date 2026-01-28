package io.temporal.serviceclient;

import static io.temporal.internal.WorkflowThreadMarker.enforceNonWorkflowThread;

import io.temporal.api.operatorservice.v1.OperatorServiceGrpc;
import io.temporal.internal.WorkflowThreadMarker;

/** Initializes and holds gRPC blocking and future stubs. */
public interface OperatorServiceStubs
    extends ServiceStubs<
        OperatorServiceGrpc.OperatorServiceBlockingStub,
        OperatorServiceGrpc.OperatorServiceFutureStub> {
  String HEALTH_CHECK_SERVICE_NAME = "temporal.api.operatorservice.v1.OperatorService";

  /**
   * @deprecated use {@link #newLocalServiceStubs()}
   */
  static OperatorServiceStubs newInstance() {
    return newLocalServiceStubs();
  }

  /**
   * @deprecated use {@link #newServiceStubs(OperatorServiceStubsOptions)}
   */
  @Deprecated
  static OperatorServiceStubs newInstance(OperatorServiceStubsOptions options) {
    return newServiceStubs(options);
  }

  /**
   * Creates OperatorService gRPC stubs pointed on to the locally running Temporal Server. The
   * Server should be available on 127.0.0.1:7233
   */
  static OperatorServiceStubs newLocalServiceStubs() {
    return newServiceStubs(OperatorServiceStubsOptions.getDefaultInstance());
  }

  /**
   * Creates OperatorService gRPC stubs<br>
   * This method creates stubs with lazy connectivity, connection is not performed during the
   * creation time and happens on the first request.
   *
   * @param options stub options to use
   */
  static OperatorServiceStubs newServiceStubs(OperatorServiceStubsOptions options) {
    enforceNonWorkflowThread();
    return WorkflowThreadMarker.protectFromWorkflowThread(
        new OperatorServiceStubsImpl(options), OperatorServiceStubs.class);
  }
}
