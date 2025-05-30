package io.temporal.testing.junit5.testWorkflowImplementationOptions;

import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;

@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class TestWorkflowImplementationOptionsCommon {

  @WorkflowInterface
  public interface HelloWorkflow {

    @WorkflowMethod
    String sayHello(String name);
  }

  /* No full Exceptionimplementation. Just for testing TestWorkflowExtension::registerWorkflowImplementationTypes*/
  public static class TestException extends RuntimeException {
    public TestException(String message) {
      super(message);
    }
  }

  public static class HelloWorkflowImpl implements HelloWorkflow {

    private static final Logger logger = Workflow.getLogger(HelloWorkflowImpl.class);

    @Override
    public String sayHello(String name) {
      logger.info("Hello, {}", name);
      throw new TestException("Hello World");
    }
  }
}
