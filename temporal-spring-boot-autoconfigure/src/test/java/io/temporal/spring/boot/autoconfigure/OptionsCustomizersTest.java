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
import io.temporal.worker.WorkerFactoryOptions;
import java.util.List;
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

@SpringBootTest(classes = OptionsCustomizersTest.Configuration.class)
@ActiveProfiles(profiles = "auto-discovery-by-task-queue")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class OptionsCustomizersTest {
  @Autowired ConfigurableApplicationContext applicationContext;

  @Autowired List<TemporalOptionsCustomizer<?>> customizers;
  @Autowired WorkerOptionsCustomizer workerCustomizer;
  @Autowired WorkflowImplementationOptionsCustomizer workflowImplementationOptionsCustomizer;

  @BeforeEach
  void setUp() {
    applicationContext.start();
  }

  @Test
  @Timeout(value = 10)
  public void testCustomizersGotCalled() {
    assertEquals(5, customizers.size());
    customizers.forEach(c -> verify(c).customize(any()));
    verify(workerCustomizer).customize(any(), eq("UnitTest"), eq("UnitTest"));
    verify(workflowImplementationOptionsCustomizer)
        .customize(any(), any(), eq(TestWorkflowImpl.class), any());
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
    public WorkerOptionsCustomizer workerCustomizer() {
      WorkerOptionsCustomizer mock = mock(WorkerOptionsCustomizer.class);
      when(mock.customize(any())).thenAnswer(invocation -> invocation.getArgument(0)).getMock();
      when(mock.customize(any(), any(), any()))
          .thenAnswer(invocation -> invocation.getArgument(0))
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
