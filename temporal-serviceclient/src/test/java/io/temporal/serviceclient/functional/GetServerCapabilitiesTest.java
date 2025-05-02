package io.temporal.serviceclient.functional;

import static org.junit.Assert.*;

import io.temporal.api.workflowservice.v1.GetSystemInfoResponse;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import org.junit.Rule;
import org.junit.Test;

public class GetServerCapabilitiesTest {

  @Rule public SDKTestWorkflowRule testWorkflowRule = SDKTestWorkflowRule.newBuilder().build();

  @Test
  public void trivial() {
    GetSystemInfoResponse.Capabilities capabilities =
        testWorkflowRule.getWorkflowServiceStubs().getServerCapabilities().get();
    assertNotNull(capabilities);
  }
}
