package io.temporal.testing;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;

public class TestWorkflowEnvironmentTest {

  @Rule
  public TestWatcher watchman =
      new TestWatcher() {
        @Override
        protected void failed(Throwable e, Description description) {
          if (testEnv != null) {
            System.err.println(testEnv.getDiagnostics());
            testEnv.close();
          }
        }
      };

  private TestWorkflowEnvironment testEnv;

  @Before
  public void setUp() {
    testEnv = TestWorkflowEnvironment.newInstance();
    testEnv.start();
  }

  @After
  public void tearDown() {
    testEnv.close();
  }

  @Test
  public void testGetExecutionHistoryWhenWorkflowNotFound() {
    Status status =
        Assert.assertThrows(
                StatusRuntimeException.class,
                () -> {
                  GetWorkflowExecutionHistoryRequest request =
                      GetWorkflowExecutionHistoryRequest.newBuilder()
                          .setExecution(
                              WorkflowExecution.newBuilder()
                                  .setWorkflowId("does not exist")
                                  .build())
                          .build();
                  testEnv
                      .getWorkflowServiceStubs()
                      .blockingStub()
                      .getWorkflowExecutionHistory(request);
                })
            .getStatus();

    Assert.assertEquals(Status.Code.NOT_FOUND, status.getCode());
  }
}
