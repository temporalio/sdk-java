package io.temporal.workflow.sdkTestWorkflowRuleTests;

import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.testing.internal.TracingWorkerInterceptor;
import io.temporal.worker.WorkerFactoryOptions;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class SDKWorkflowRuleInterceptorTest2 {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkerFactoryOptions(WorkerFactoryOptions.getDefaultInstance())
          .build();

  @Test
  public void testWorkerInterceptorWorkerFactoryOptionsSet() {
    Assert.assertNotNull(testWorkflowRule.getInterceptor(TracingWorkerInterceptor.class));
  }
}
