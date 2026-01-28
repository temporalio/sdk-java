package io.temporal.serviceclient;

import static io.temporal.internal.WorkflowThreadMarker.enforceNonWorkflowThread;

import io.temporal.api.testservice.v1.TestServiceGrpc;
import io.temporal.internal.WorkflowThreadMarker;

public interface TestServiceStubs
    extends ServiceStubs<
        TestServiceGrpc.TestServiceBlockingStub, TestServiceGrpc.TestServiceFutureStub> {
  String HEALTH_CHECK_SERVICE_NAME = "temporal.api.testservice.v1.TestService";

  static TestServiceStubs newServiceStubs(TestServiceStubsOptions options) {
    enforceNonWorkflowThread();
    return WorkflowThreadMarker.protectFromWorkflowThread(
        new TestServiceStubsImpl(options), TestServiceStubs.class);
  }
}
