package io.temporal.spring.boot.autoconfigure;

import static org.junit.jupiter.api.Assertions.*;

import io.temporal.client.WorkflowClient;
import io.temporal.spring.boot.autoconfigure.properties.TemporalProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(classes = MTLSWithServerNameOverrideTest.Configuration.class)
@ActiveProfiles(profiles = "mtls-with-server-name-override")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class MTLSWithServerNameOverrideTest {
  @Autowired ConfigurableApplicationContext applicationContext;
  @Autowired TemporalProperties temporalProperties;
  @Autowired WorkflowClient workflowClient;

  @BeforeEach
  void setUp() {
    applicationContext.start();
  }

  @Test
  public void testProperties() {
    assertEquals("myservername", temporalProperties.getConnection().getMTLS().getServerName());
  }

  @Test
  public void testClient() {
    assertEquals(
        "myservername", workflowClient.getWorkflowServiceStubs().getRawChannel().authority());
  }

  @ComponentScan(
      excludeFilters =
          @ComponentScan.Filter(
              pattern = "io\\.temporal\\.spring\\.boot\\.autoconfigure\\.byworkername\\..*",
              type = FilterType.REGEX))
  public static class Configuration {}
}
