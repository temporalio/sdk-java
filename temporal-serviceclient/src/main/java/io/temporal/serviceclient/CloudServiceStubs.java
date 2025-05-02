package io.temporal.serviceclient;

import static io.temporal.internal.WorkflowThreadMarker.enforceNonWorkflowThread;

import io.temporal.api.cloud.cloudservice.v1.CloudServiceGrpc;
import io.temporal.internal.WorkflowThreadMarker;

/**
 * Initializes and holds gRPC blocking and future stubs.
 *
 * <p>WARNING: The cloud service is currently experimental.
 */
public interface CloudServiceStubs
    extends ServiceStubs<
        CloudServiceGrpc.CloudServiceBlockingStub, CloudServiceGrpc.CloudServiceFutureStub> {
  String HEALTH_CHECK_SERVICE_NAME = "temporal.api.cloud.cloudservice.v1.CloudService";

  /** Creates CloudService gRPC stubs pointed on to Temporal Cloud. */
  static CloudServiceStubs newCloudServiceStubs() {
    return newServiceStubs(CloudServiceStubsOptions.getDefaultInstance());
  }

  /**
   * Creates CloudService gRPC stubs<br>
   * This method creates stubs with lazy connectivity, connection is not performed during the
   * creation time and happens on the first request.
   *
   * @param options stub options to use
   */
  static CloudServiceStubs newServiceStubs(CloudServiceStubsOptions options) {
    enforceNonWorkflowThread();
    return WorkflowThreadMarker.protectFromWorkflowThread(
        new CloudServiceStubsImpl(options), CloudServiceStubs.class);
  }
}
