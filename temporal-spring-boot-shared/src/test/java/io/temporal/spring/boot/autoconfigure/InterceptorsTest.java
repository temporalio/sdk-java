package io.temporal.spring.boot.autoconfigure;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.interceptors.WorkerInterceptor;
import io.temporal.common.interceptors.WorkerInterceptorBase;
import io.temporal.spring.boot.autoconfigure.bytaskqueue.TestWorkflow;
import io.temporal.spring.boot.autoconfigure.template.NamespaceTemplate;
import io.temporal.testing.TestWorkflowEnvironment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(classes = InterceptorsTest.Configuration.class)
@ActiveProfiles(profiles = "auto-discovery-by-task-queue")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class InterceptorsTest {
  @Autowired ConfigurableApplicationContext applicationContext;

  @Autowired TestWorkflowEnvironment testWorkflowEnvironment;

  @Autowired WorkflowClient workflowClient;

  @Autowired WorkerInterceptor spyWorkerInterceptor;

  @Autowired NamespaceTemplate namespaceTemplate;

  @BeforeEach
  void setUp() {
    applicationContext.start();
  }

  @Test
  @Timeout(value = 10)
  public void testCustomInterceptorBeanIsPickedUpByTestWorkflowEnvironment() {
    TestWorkflow testWorkflow =
        workflowClient.newWorkflowStub(
            TestWorkflow.class, WorkflowOptions.newBuilder().setTaskQueue("UnitTest").build());
    testWorkflow.execute("input");
    verify(spyWorkerInterceptor, atLeastOnce()).interceptWorkflow(any());
  }

  @ComponentScan(
      excludeFilters =
          @ComponentScan.Filter(
              pattern = "io\\.temporal\\.spring\\.boot\\.autoconfigure\\.byworkername\\..*",
              type = FilterType.REGEX))
  public static class Configuration {

    @Bean
    public WorkerInterceptor spyWorkerInterceptor() {
      WorkerInterceptor result = spy(new SpringWorkerInterceptor());
      return result;
    }

    public static class SpringWorkerInterceptor extends WorkerInterceptorBase {
      SpringWorkerInterceptor() {
        super();
      }
    }
  }
}
