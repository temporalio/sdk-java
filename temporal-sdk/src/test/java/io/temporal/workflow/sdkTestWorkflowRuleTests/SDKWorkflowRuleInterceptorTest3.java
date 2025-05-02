package io.temporal.workflow.sdkTestWorkflowRuleTests;

import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.testing.internal.TracingWorkerInterceptor;
import io.temporal.worker.WorkerFactoryOptions;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class SDKWorkflowRuleInterceptorTest3 {

  TracingWorkerInterceptor tracingWorkerInterceptor =
      new TracingWorkerInterceptor(new TracingWorkerInterceptor.FilteredTrace());

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkerFactoryOptions(
              WorkerFactoryOptions.newBuilder()
                  .setWorkerInterceptors(tracingWorkerInterceptor)
                  .build())
          .build();

  @Test
  public void testWorkerInterceptorWorkerFactoryOptionsSetWithInterceptor() {
    Assert.assertSame(
        tracingWorkerInterceptor, testWorkflowRule.getInterceptor(TracingWorkerInterceptor.class));
  }
}
