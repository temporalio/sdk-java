package io.temporal.spring.boot.autoconfigure;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.temporal.client.WorkflowClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(classes = CustomClientConfigTest.Configuration.class)
@ActiveProfiles(profiles = "custom-namespace")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class CustomClientConfigTest {
  @Autowired ConfigurableApplicationContext applicationContext;

  @Autowired protected WorkflowClient workflowClient;

  @BeforeEach
  void setUp() {
    applicationContext.start();
  }

  @Test
  @Timeout(value = 10)
  public void shouldCustomizeNamespace() {
    assertEquals("custom", workflowClient.getOptions().getNamespace());
  }

  @EnableAutoConfiguration
  public static class Configuration {}
}
