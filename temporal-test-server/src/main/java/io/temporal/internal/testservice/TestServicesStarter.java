package io.temporal.internal.testservice;

import io.grpc.BindableService;
import java.io.Closeable;
import java.time.Clock;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class TestServicesStarter implements Closeable {
  private final SelfAdvancingTimerImpl selfAdvancingTimer;
  private final TestVisibilityStore visibilityStore = new TestVisibilityStoreImpl();
  private final TestNexusEndpointStore nexusEndpointStore = new TestNexusEndpointStoreImpl();
  private final TestWorkflowStore workflowStore;
  private final TestOperatorService operatorService;
  private final TestWorkflowService workflowService;
  private final TestService testService;
  private final List<BindableService> services;

  /**
   * @param lockTimeSkipping true if the time skipping should be locked (disabled) by default after
   *     creation of the server
   * @param initialTimeMillis initial timestamp for the test server, {@link
   *     System#currentTimeMillis()} will be used if 0.
   */
  public TestServicesStarter(boolean lockTimeSkipping, long initialTimeMillis) {
    this.selfAdvancingTimer =
        new SelfAdvancingTimerImpl(initialTimeMillis, Clock.systemDefaultZone());
    this.workflowStore = new TestWorkflowStoreImpl(this.selfAdvancingTimer);
    this.operatorService = new TestOperatorService(this.visibilityStore, this.nexusEndpointStore);
    this.testService =
        new TestService(this.workflowStore, this.selfAdvancingTimer, lockTimeSkipping);
    this.workflowService =
        new TestWorkflowService(
            this.workflowStore,
            this.visibilityStore,
            this.nexusEndpointStore,
            this.selfAdvancingTimer);
    this.services = Arrays.asList(this.operatorService, this.testService, this.workflowService);
  }

  @Override
  public void close() {
    workflowService.close();
    operatorService.close();
    testService.close();
    visibilityStore.close();
  }

  public TestOperatorService getOperatorService() {
    return operatorService;
  }

  public TestWorkflowService getWorkflowService() {
    return workflowService;
  }

  public TestService getTestService() {
    return testService;
  }

  public List<BindableService> getServices() {
    return Collections.unmodifiableList(services);
  }
}
