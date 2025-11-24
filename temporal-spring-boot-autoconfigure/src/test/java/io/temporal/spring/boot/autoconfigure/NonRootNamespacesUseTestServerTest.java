package io.temporal.spring.boot.autoconfigure;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.grpc.health.v1.HealthCheckResponse;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.testing.TestWorkflowEnvironment;
import javax.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

@SpringBootTest(
    classes = NonRootNamespacesUseTestServerTest.Configuration.class,
    properties = {
      "spring.temporal.test-server.enabled=true",
      "spring.temporal.connection.target=127.0.0.1:7233",
      "spring.temporal.start-workers=false",
      "spring.temporal.namespaces[0].namespace=pomegranate",
      "spring.temporal.namespaces[0].alias=pomegranate"
    })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class NonRootNamespacesUseTestServerTest {

  @Autowired ConfigurableApplicationContext applicationContext;

  @Autowired
  @Qualifier("temporalTestWorkflowEnvironment")
  TestWorkflowEnvironment testWorkflowEnvironment;

  @Resource(name = "pomegranateWorkflowServiceStubs")
  WorkflowServiceStubs pomegranateWorkflowServiceStubs;

  @BeforeEach
  void setUp() {
    applicationContext.start();
  }

  @Test
  @Timeout(10)
  public void nonRootNamespaceUsesInMemoryTestServer() {
    HealthCheckResponse envHealth =
        testWorkflowEnvironment.getWorkflowClient().getWorkflowServiceStubs().healthCheck();
    HealthCheckResponse pomegranateHealth = pomegranateWorkflowServiceStubs.healthCheck();
    assertEquals(HealthCheckResponse.ServingStatus.SERVING, envHealth.getStatus());
    assertEquals(HealthCheckResponse.ServingStatus.SERVING, pomegranateHealth.getStatus());
  }

  @ComponentScan(
      excludeFilters =
          @ComponentScan.Filter(
              pattern =
                  "io\\.temporal\\.spring\\.boot\\.autoconfigure\\.(byworkername|bytaskqueue)\\..*",
              type = FilterType.REGEX))
  public static class Configuration {}
}
