package io.temporal.workflow;

import static org.junit.Assert.assertEquals;

import io.temporal.client.WorkflowClientOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.workflow.shared.TestWorkflows;
import java.util.Collections;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.slf4j.MDC;

@RunWith(Enclosed.class)
public class WorkerFactoryContextPropagationTest {

  public static class FactoryOnly {
    @Rule
    public SDKTestWorkflowRule testWorkflowRule =
        SDKTestWorkflowRule.newBuilder()
            .setWorkerFactoryOptions(
                WorkerFactoryOptions.newBuilder()
                    .setContextPropagators(
                        Collections.singletonList(
                            new ContextPropagationTest.TestContextPropagator()))
                    .build())
            .setWorkflowTypes(FactoryContextPropagationThreadWorkflowImpl.class)
            .build();

    @Test
    public void testThreadContextPropagationFromWorkerFactoryOptions() {
      TestWorkflows.TestWorkflow1 workflow =
          testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.TestWorkflow1.class);
      assertEquals("testing123", workflow.execute("testing123"));
    }

    public static class FactoryContextPropagationThreadWorkflowImpl
        implements TestWorkflows.TestWorkflow1 {

      @Override
      public String execute(String input) {
        MDC.put("test", input);
        return Async.function(() -> MDC.get("test")).get();
      }
    }
  }

  public static class DuplicateNames {
    private final ContextPropagationTest.TestContextPropagator clientPropagator =
        new ContextPropagationTest.TestContextPropagator();
    private final ContextPropagationTest.TestContextPropagator factoryPropagator =
        new ContextPropagationTest.TestContextPropagator() {
          @Override
          public String getName() {
            return clientPropagator.getName();
          }

          @Override
          public Object getCurrentContext() {
            throw new AssertionError("Duplicate factory context propagator was used");
          }
        };

    @Rule
    public SDKTestWorkflowRule testWorkflowRule =
        SDKTestWorkflowRule.newBuilder()
            .setWorkflowClientOptions(
                WorkflowClientOptions.newBuilder()
                    .setContextPropagators(Collections.singletonList(clientPropagator))
                    .build())
            .setWorkerFactoryOptions(
                WorkerFactoryOptions.newBuilder()
                    .setContextPropagators(Collections.singletonList(factoryPropagator))
                    .build())
            .setWorkflowTypes(FactoryOnly.FactoryContextPropagationThreadWorkflowImpl.class)
            .build();

    @Test
    public void testDuplicateContextPropagatorsAreIgnored() {
      TestWorkflows.TestWorkflow1 workflow =
          testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.TestWorkflow1.class);
      assertEquals("testing123", workflow.execute("testing123"));
    }
  }
}
