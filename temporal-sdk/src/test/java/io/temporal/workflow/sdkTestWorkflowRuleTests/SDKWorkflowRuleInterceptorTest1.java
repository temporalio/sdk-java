package io.temporal.workflow.sdkTestWorkflowRuleTests;

import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.testing.internal.TracingWorkerInterceptor;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class SDKWorkflowRuleInterceptorTest1 {

  @Rule public SDKTestWorkflowRule testWorkflowRule = SDKTestWorkflowRule.newBuilder().build();

  @Test
  public void testWorkerInterceptorWorkerFactoryOptionsNotSet() {
    Assert.assertNotNull(testWorkflowRule.getInterceptor(TracingWorkerInterceptor.class));
  }
}
