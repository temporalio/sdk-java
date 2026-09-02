package io.temporal.workerFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.testing.CloudTestExclusion.NeedsCloudAdaptation;
import io.temporal.testing.CloudTestExclusionNote;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.internal.ExternalServiceTestConfigurator;
import io.temporal.worker.WorkerFactory;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

public class WorkerFactoryTests {

  private static final boolean useExternalService =
      ExternalServiceTestConfigurator.isUseExternalService();

  @BeforeClass
  public static void beforeClass() {
    Assume.assumeTrue(useExternalService);
  }

  private WorkflowServiceStubs service;
  private WorkerFactory factory;

  @Before
  public void setUp() {
    TestEnvironmentOptions environmentOptions =
        ExternalServiceTestConfigurator.configuredTestEnvironmentOptions().build();
    service = newServiceStubs(environmentOptions);
    WorkflowClient client = newWorkflowClient(service, environmentOptions);
    factory = WorkerFactory.newInstance(client);
  }

  @After
  public void tearDown() throws InterruptedException {
    factory.shutdownNow();
    factory.awaitTermination(5, TimeUnit.SECONDS);
    service.shutdownNow();
    service.awaitTermination(5, TimeUnit.SECONDS);
  }

  @Test
  public void whenAFactoryIsStartedAllWorkersStart() {
    factory.newWorker("task1");
    factory.newWorker("task2");

    factory.start();
    assertTrue(factory.isStarted());
    factory.shutdown();
    factory.awaitTermination(1, TimeUnit.SECONDS);
  }

  @Test
  public void whenAFactoryIsShutdownAllWorkersAreShutdown() {
    factory.newWorker("task1");
    factory.newWorker("task2");

    assertFalse(factory.isStarted());
    factory.start();
    assertTrue(factory.isStarted());
    assertFalse(factory.isShutdown());
    factory.shutdown();
    factory.awaitTermination(1, TimeUnit.MILLISECONDS);

    assertTrue(factory.isShutdown());
    factory.shutdown();
    assertTrue(factory.isShutdown());
    factory.awaitTermination(1, TimeUnit.SECONDS);
  }

  @Test
  public void aFactoryCanBeStartedMoreThanOnce() {
    factory.start();
    factory.start();
    factory.shutdown();
    factory.awaitTermination(1, TimeUnit.SECONDS);
  }

  @Test(expected = IllegalStateException.class)
  public void aFactoryCannotBeStartedAfterShutdown() {
    factory.newWorker("task1");

    factory.shutdown();
    factory.awaitTermination(1, TimeUnit.MILLISECONDS);
    factory.start();
  }

  @Test(expected = IllegalStateException.class)
  public void workersCannotBeCreatedAfterFactoryHasStarted() {
    factory.newWorker("task1");

    factory.start();

    try {
      factory.newWorker("task2");
    } finally {
      factory.shutdown();
      factory.awaitTermination(1, TimeUnit.SECONDS);
    }
  }

  @Test(expected = IllegalStateException.class)
  public void workersCannotBeCreatedAfterFactoryIsShutdown() {
    factory.newWorker("task1");

    factory.shutdown();
    factory.awaitTermination(1, TimeUnit.MILLISECONDS);
    try {
      factory.newWorker("task2");
    } finally {
      factory.shutdown();
      factory.awaitTermination(1, TimeUnit.SECONDS);
    }
  }

  @Test
  public void factoryCanBeShutdownMoreThanOnce() {
    factory.newWorker("task1");

    factory.shutdown();
    factory.awaitTermination(1, TimeUnit.MILLISECONDS);
    factory.shutdown();
    factory.awaitTermination(1, TimeUnit.MILLISECONDS);
  }

  @Test
  @CloudTestExclusionNote("Cloud hides nonexistent namespaces from namespace-scoped credentials.")
  @Category(NeedsCloudAdaptation.class)
  public void startFailsOnNonexistentNamespace() {
    TestEnvironmentOptions environmentOptions =
        ExternalServiceTestConfigurator.configuredTestEnvironmentOptions().build();
    WorkflowServiceStubs serviceLocal = newServiceStubs(environmentOptions);
    WorkflowClient clientLocal =
        WorkflowClient.newInstance(
            serviceLocal,
            newWorkflowClientOptions(environmentOptions).setNamespace("i_dont_exist").build());
    WorkerFactory factoryLocal = WorkerFactory.newInstance(clientLocal);
    factoryLocal.newWorker("task-queue");

    StatusRuntimeException ex = assertThrows(StatusRuntimeException.class, factoryLocal::start);
    assertEquals(Status.Code.NOT_FOUND, ex.getStatus().getCode());

    factoryLocal.shutdownNow();
    factoryLocal.awaitTermination(5, TimeUnit.SECONDS);
    serviceLocal.shutdownNow();
    serviceLocal.awaitTermination(5, TimeUnit.SECONDS);
  }

  private static WorkflowServiceStubs newServiceStubs(TestEnvironmentOptions environmentOptions) {
    WorkflowServiceStubsOptions configuredOptions =
        environmentOptions.getWorkflowServiceStubsOptions();
    WorkflowServiceStubsOptions.Builder serviceOptions =
        configuredOptions == null
            ? WorkflowServiceStubsOptions.newBuilder()
            : WorkflowServiceStubsOptions.newBuilder(configuredOptions);
    if (environmentOptions.getTarget() != null) {
      serviceOptions.setTarget(environmentOptions.getTarget());
    }
    return WorkflowServiceStubs.newServiceStubs(serviceOptions.build());
  }

  private static WorkflowClient newWorkflowClient(
      WorkflowServiceStubs service, TestEnvironmentOptions environmentOptions) {
    WorkflowClientOptions configuredOptions = environmentOptions.getWorkflowClientOptions();
    return configuredOptions == null
        ? WorkflowClient.newInstance(service)
        : WorkflowClient.newInstance(service, configuredOptions);
  }

  private static WorkflowClientOptions.Builder newWorkflowClientOptions(
      TestEnvironmentOptions environmentOptions) {
    WorkflowClientOptions configuredOptions = environmentOptions.getWorkflowClientOptions();
    return configuredOptions == null
        ? WorkflowClientOptions.newBuilder()
        : WorkflowClientOptions.newBuilder(configuredOptions);
  }
}
