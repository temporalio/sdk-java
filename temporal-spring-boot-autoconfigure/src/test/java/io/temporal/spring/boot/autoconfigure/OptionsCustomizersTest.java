package io.temporal.spring.boot.autoconfigure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.temporal.client.WorkflowClientOptions;
import io.temporal.spring.boot.TemporalOptionsCustomizer;
import io.temporal.spring.boot.WorkerOptionsCustomizer;
import io.temporal.spring.boot.WorkflowImplementationOptionsCustomizer;
import io.temporal.spring.boot.autoconfigure.bytaskqueue.TestWorkflowImpl;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.worker.WorkerOptions;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(classes = OptionsCustomizersTest.Configuration.class)
@ActiveProfiles(profiles = "auto-discovery-by-task-queue")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class OptionsCustomizersTest {
  @Autowired ConfigurableApplicationContext applicationContext;

  @Autowired List<TemporalOptionsCustomizer<?>> customizers;
  @Autowired Map<String, TemporalOptionsCustomizer<?>> customizersMap;
  @Autowired WorkerOptionsCustomizer firstWorkerCustomizer;
  @Autowired WorkflowImplementationOptionsCustomizer workflowImplementationOptionsCustomizer;
  @Autowired WorkerFactory temporalWorkerFactory;

  @BeforeEach
  void setUp() {
    applicationContext.start();
  }

  @Test
  @Timeout(value = 10)
  public void testCustomizersGotCalled() {
    assertEquals(7, customizers.size());
    customizers.forEach(c -> verify(c).customize(any()));
    verify(firstWorkerCustomizer).customize(any(), eq("UnitTest"), eq("UnitTest"));
    verify(workflowImplementationOptionsCustomizer)
        .customize(any(), any(), eq(TestWorkflowImpl.class), any());
    assertEquals(
        "test-identity-3",
        temporalWorkerFactory.getWorker("UnitTest").getWorkerOptions().getIdentity());
  }

  @ComponentScan(
      excludeFilters =
          @ComponentScan.Filter(
              pattern = "io\\.temporal\\.spring\\.boot\\.autoconfigure\\.byworkername\\..*",
              type = FilterType.REGEX))
  public static class Configuration {

    @Bean
    public TemporalOptionsCustomizer<WorkflowClientOptions.Builder> clientCustomizer() {
      return getReturningMock();
    }

    @Bean
    public TemporalOptionsCustomizer<TestEnvironmentOptions.Builder> testEnvironmentCustomizer() {
      return getReturningMock();
    }

    @Bean
    public TemporalOptionsCustomizer<WorkerFactoryOptions.Builder> workerFactoryCustomizer() {
      return getReturningMock();
    }

    @Bean
    public WorkflowImplementationOptionsCustomizer WorkflowImplementationCustomizer() {
      WorkflowImplementationOptionsCustomizer mock =
          mock(WorkflowImplementationOptionsCustomizer.class);
      when(mock.customize(any())).thenAnswer(invocation -> invocation.getArgument(0)).getMock();
      when(mock.customize(any(), any(), any(), any()))
          .thenAnswer(invocation -> invocation.getArgument(0))
          .getMock();
      return mock;
    }

    @Bean
    @org.springframework.core.annotation.Order(3)
    public WorkerOptionsCustomizer lastWorkerCustomizer() {
      WorkerOptionsCustomizer mock = mock(WorkerOptionsCustomizer.class);
      when(mock.customize(any())).thenAnswer(invocation -> invocation.getArgument(0)).getMock();
      when(mock.customize(any(), any(), any()))
          .thenAnswer(
              invocation -> {
                WorkerOptions options = ((WorkerOptions.Builder) invocation.getArgument(0)).build();
                assertEquals("test-identity-2", options.getIdentity());
                return ((WorkerOptions.Builder) invocation.getArgument(0))
                    .setIdentity("test-identity-3");
              })
          .getMock();
      return mock;
    }

    @Bean
    @org.springframework.core.annotation.Order(2)
    public WorkerOptionsCustomizer middleWorkerCustomizer() {
      WorkerOptionsCustomizer mock = mock(WorkerOptionsCustomizer.class);
      when(mock.customize(any())).thenAnswer(invocation -> invocation.getArgument(0)).getMock();
      when(mock.customize(any(), any(), any()))
          .thenAnswer(
              invocation -> {
                WorkerOptions options = ((WorkerOptions.Builder) invocation.getArgument(0)).build();
                assertEquals("test-identity-1", options.getIdentity());
                return ((WorkerOptions.Builder) invocation.getArgument(0))
                    .setIdentity("test-identity-2");
              })
          .getMock();
      return mock;
    }

    @Bean
    @org.springframework.core.annotation.Order(1)
    public WorkerOptionsCustomizer firstWorkerCustomizer() {
      WorkerOptionsCustomizer mock = mock(WorkerOptionsCustomizer.class);
      when(mock.customize(any())).thenAnswer(invocation -> invocation.getArgument(0)).getMock();
      when(mock.customize(any(), any(), any()))
          .thenAnswer(
              invocation ->
                  ((WorkerOptions.Builder) invocation.getArgument(0))
                      .setIdentity("test-identity-1"))
          .getMock();
      return mock;
    }

    @SuppressWarnings("unchecked")
    private <T> TemporalOptionsCustomizer<T> getReturningMock() {
      return when(mock(TemporalOptionsCustomizer.class).customize(any()))
          .thenAnswer(invocation -> invocation.getArgument(0))
          .getMock();
    }
  }
}
