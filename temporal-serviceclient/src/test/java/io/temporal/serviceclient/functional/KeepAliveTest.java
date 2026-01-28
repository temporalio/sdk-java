package io.temporal.serviceclient.functional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import java.time.Duration;
import org.junit.Rule;
import org.junit.Test;

public class KeepAliveTest {
  @Rule public SDKTestWorkflowRule testWorkflowRule = SDKTestWorkflowRule.newBuilder().build();

  @Test
  public void testKeepAliveOnByDefault() {
    WorkflowServiceStubsOptions options = testWorkflowRule.getWorkflowServiceStubs().getOptions();
    assertTrue(options.getEnableKeepAlive());
    assertTrue(options.getKeepAlivePermitWithoutStream());
    assertEquals(Duration.ofSeconds(30), options.getKeepAliveTime());
    assertEquals(Duration.ofSeconds(15), options.getKeepAliveTimeout());
  }
}
