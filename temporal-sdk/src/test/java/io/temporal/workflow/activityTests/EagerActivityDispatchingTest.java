package io.temporal.workflow.activityTests;

import static org.junit.Assert.*;
import static org.junit.Assume.*;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.MethodDescriptor;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedRequest;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.internal.Config;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.testUtils.CountingSlotSupplier;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.internal.ExternalServiceTestConfigurator;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerOptions;
import io.temporal.worker.tuning.*;
import io.temporal.workflow.*;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.*;

public class EagerActivityDispatchingTest {
  private static final String TASK_QUEUE = "test-eager-activity-dispatch";
  private TestWorkflowEnvironment env;
  private ArrayList<WorkerFactory> workerFactories;
  private final EagerActivityRequestInterceptor eagerActivityRequestInterceptor =
      new EagerActivityRequestInterceptor();

  private final TestActivitiesImpl activitiesImpl = new TestActivitiesImpl();
  CountingSlotSupplier<WorkflowSlotInfo> workflowTaskSlotSupplier = new CountingSlotSupplier<>(100);
  CountingSlotSupplier<ActivitySlotInfo> activityTaskSlotSupplier = new CountingSlotSupplier<>(100);
  CountingSlotSupplier<LocalActivitySlotInfo> localActivitySlotSupplier =
      new CountingSlotSupplier<>(100);
  CountingSlotSupplier<NexusSlotInfo> nexusSlotSupplier = new CountingSlotSupplier<>(100);

  @Before
  public void setUp() throws Exception {
    eagerActivityRequestInterceptor.reset();
    TestEnvironmentOptions.Builder environmentOptions =
        ExternalServiceTestConfigurator.configuredTestEnvironmentOptions();
    WorkflowServiceStubsOptions configuredServiceOptions =
        environmentOptions.build().getWorkflowServiceStubsOptions();
    WorkflowServiceStubsOptions.Builder serviceOptions =
        configuredServiceOptions == null
            ? WorkflowServiceStubsOptions.newBuilder()
            : WorkflowServiceStubsOptions.newBuilder(configuredServiceOptions);
    this.env =
        TestWorkflowEnvironment.newInstance(
            environmentOptions
                .setWorkflowServiceStubsOptions(
                    serviceOptions
                        .setGrpcClientInterceptors(
                            Collections.singletonList(eagerActivityRequestInterceptor))
                        .build())
                .build());
    this.workerFactories = new ArrayList<>();
  }

  @After
  public void tearDown() throws Exception {
    for (WorkerFactory workerFactory : this.workerFactories) workerFactory.shutdownNow();
    for (WorkerFactory workerFactory : this.workerFactories)
      workerFactory.awaitTermination(10, TimeUnit.SECONDS);
    this.workerFactories = null;

    env.close();
    assertEquals(
        workflowTaskSlotSupplier.reservedCount.get(), workflowTaskSlotSupplier.releasedCount.get());
    assertEquals(
        activityTaskSlotSupplier.reservedCount.get(), activityTaskSlotSupplier.releasedCount.get());
    assertEquals(
        localActivitySlotSupplier.reservedCount.get(),
        localActivitySlotSupplier.releasedCount.get());
  }

  private void setupWorker(
      String workerIdentity, WorkerOptions.Builder workerOptions, boolean registerWorkflows) {
    WorkflowClient workflowClient =
        WorkflowClient.newInstance(
            env.getWorkflowServiceStubs(),
            env.getWorkflowClient().getOptions().toBuilder().setIdentity(workerIdentity).build());
    WorkerFactory workerFactory = WorkerFactory.newInstance(workflowClient);
    workerFactories.add(workerFactory);

    workerOptions.setWorkerTuner(
        new CompositeTuner(
            workflowTaskSlotSupplier,
            activityTaskSlotSupplier,
            localActivitySlotSupplier,
            nexusSlotSupplier));
    Worker worker = workerFactory.newWorker(TASK_QUEUE, workerOptions.build());
    worker.registerActivitiesImplementations(activitiesImpl);
    if (registerWorkflows)
      worker.registerWorkflowImplementationTypes(EagerActivityTestWorkflowImpl.class);

    workerFactory.start();
  }

  @Test
  public void testEagerActivities() {
    assumeTrue(
        "Test Server doesn't support eager activity dispatch",
        SDKTestWorkflowRule.useExternalService);

    setupWorker(
        "worker1",
        WorkerOptions.newBuilder()
            .setMaxConcurrentWorkflowTaskPollers(2)
            .setMaxConcurrentActivityTaskPollers(1)
            .setDisableEagerExecution(false),
        true);
    setupWorker(
        "worker2", WorkerOptions.newBuilder().setMaxConcurrentActivityTaskPollers(2), false);

    EagerActivityTestWorkflow workflowStub =
        env.getWorkflowClient()
            .newWorkflowStub(
                EagerActivityTestWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build());
    workflowStub.execute(true);

    WorkflowExecutionHistory history =
        env.getWorkflowClient()
            .fetchHistory(WorkflowStub.fromTyped(workflowStub).getExecution().getWorkflowId());
    Set<String> activityTaskStartedEventIdentity =
        history.getEvents().stream()
            .filter(HistoryEvent::hasActivityTaskStartedEventAttributes)
            .map(x -> x.getActivityTaskStartedEventAttributes().getIdentity())
            .collect(Collectors.toSet());

    assertEquals(1, activityTaskStartedEventIdentity.size());
    assertTrue(activityTaskStartedEventIdentity.contains("worker1"));
    assertFalse(activityTaskStartedEventIdentity.contains("worker2"));
  }

  @Test
  public void testMaxEagerActivityReservationsPerWorkflowTask() {
    setupWorker(
        "worker1",
        WorkerOptions.newBuilder()
            .setMaxEagerActivityReservationsPerWorkflowTask(2)
            .setDisableEagerExecution(false),
        true);

    EagerActivityTestWorkflow workflowStub =
        env.getWorkflowClient()
            .newWorkflowStub(
                EagerActivityTestWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build());
    workflowStub.execute(true);

    assertEquals(2, eagerActivityRequestInterceptor.getEagerActivityRequestCount());
  }

  @Test
  public void testNoEagerActivitiesIfDisabledOnWorker() {
    assumeTrue(
        "Test Server doesn't support eager activity dispatch",
        SDKTestWorkflowRule.useExternalService);

    setupWorker(
        "worker1",
        WorkerOptions.newBuilder()
            .setMaxConcurrentWorkflowTaskPollers(2)
            .setMaxConcurrentActivityTaskPollers(1)
            .setDisableEagerExecution(true),
        true);
    setupWorker(
        "worker2", WorkerOptions.newBuilder().setMaxConcurrentActivityTaskPollers(2), false);

    EagerActivityTestWorkflow workflowStub =
        env.getWorkflowClient()
            .newWorkflowStub(
                EagerActivityTestWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build());
    workflowStub.execute(true);

    WorkflowExecutionHistory history =
        env.getWorkflowClient()
            .fetchHistory(WorkflowStub.fromTyped(workflowStub).getExecution().getWorkflowId());
    Set<String> activityTaskStartedEventIdentity =
        history.getEvents().stream()
            .filter(HistoryEvent::hasActivityTaskStartedEventAttributes)
            .map(x -> x.getActivityTaskStartedEventAttributes().getIdentity())
            .collect(Collectors.toSet());

    assertEquals(2, activityTaskStartedEventIdentity.size());
    assertTrue(activityTaskStartedEventIdentity.contains("worker1"));
    assertTrue(activityTaskStartedEventIdentity.contains("worker2"));
  }

  @Test
  public void testNoEagerActivitiesIfDisabledOnActivity() {
    assumeTrue(
        "Test Server doesn't support eager activity dispatch",
        SDKTestWorkflowRule.useExternalService);

    setupWorker(
        "worker1",
        WorkerOptions.newBuilder()
            .setMaxConcurrentWorkflowTaskPollers(2)
            .setMaxConcurrentActivityTaskPollers(1)
            .setDisableEagerExecution(false),
        true);
    setupWorker(
        "worker2", WorkerOptions.newBuilder().setMaxConcurrentActivityTaskPollers(2), false);

    EagerActivityTestWorkflow workflowStub =
        env.getWorkflowClient()
            .newWorkflowStub(
                EagerActivityTestWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build());
    workflowStub.execute(false);

    WorkflowExecutionHistory history =
        env.getWorkflowClient()
            .fetchHistory(WorkflowStub.fromTyped(workflowStub).getExecution().getWorkflowId());
    Set<String> activityTaskStartedEventIdentity =
        history.getEvents().stream()
            .filter(HistoryEvent::hasActivityTaskStartedEventAttributes)
            .map(x -> x.getActivityTaskStartedEventAttributes().getIdentity())
            .collect(Collectors.toSet());

    assertEquals(2, activityTaskStartedEventIdentity.size());
    assertTrue(activityTaskStartedEventIdentity.contains("worker1"));
    assertTrue(activityTaskStartedEventIdentity.contains("worker2"));
  }

  @WorkflowInterface
  public interface EagerActivityTestWorkflow {
    @WorkflowMethod
    void execute(boolean enableEagerActivityDispatch);
  }

  public static class EagerActivityTestWorkflowImpl implements EagerActivityTestWorkflow {
    @Override
    public void execute(boolean enableEagerActivityDispatch) {
      TestActivities.VariousTestActivities testActivities =
          Workflow.newActivityStub(
              TestActivities.VariousTestActivities.class,
              ActivityOptions.newBuilder()
                  .setScheduleToCloseTimeout(Duration.ofMillis(200))
                  .setDisableEagerExecution(!enableEagerActivityDispatch)
                  .build());

      ArrayList<Promise<String>> promises = new ArrayList<>();
      for (int i = 0; i < Config.EAGER_ACTIVITIES_LIMIT; i++)
        promises.add(Async.function(testActivities::activity));
      Promise.allOf(promises).get();
    }
  }

  private static class EagerActivityRequestInterceptor implements ClientInterceptor {
    private final AtomicInteger eagerActivityRequestCount = new AtomicInteger(-1);

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
        MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
      if (method == WorkflowServiceGrpc.getRespondWorkflowTaskCompletedMethod()) {
        return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
            next.newCall(method, callOptions)) {
          @Override
          public void sendMessage(ReqT message) {
            RespondWorkflowTaskCompletedRequest request =
                (RespondWorkflowTaskCompletedRequest) message;
            long activityCommandCount =
                request.getCommandsList().stream()
                    .filter(command -> command.hasScheduleActivityTaskCommandAttributes())
                    .count();
            if (activityCommandCount > 0) {
              int eagerRequestCount =
                  (int)
                      request.getCommandsList().stream()
                          .filter(command -> command.hasScheduleActivityTaskCommandAttributes())
                          .filter(
                              command ->
                                  command
                                      .getScheduleActivityTaskCommandAttributes()
                                      .getRequestEagerExecution())
                          .count();
              eagerActivityRequestCount.compareAndSet(-1, eagerRequestCount);
            }
            super.sendMessage(message);
          }
        };
      }
      return next.newCall(method, callOptions);
    }

    int getEagerActivityRequestCount() {
      return eagerActivityRequestCount.get();
    }

    void reset() {
      eagerActivityRequestCount.set(-1);
    }
  }
}
