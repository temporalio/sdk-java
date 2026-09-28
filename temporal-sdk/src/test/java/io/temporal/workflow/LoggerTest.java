package io.temporal.workflow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.google.common.base.Strings;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.internal.logging.LoggerTag;
import io.temporal.testing.CloudTestExclusion.RequiresLocalServer;
import io.temporal.testing.CloudTestExclusionNote;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactoryOptions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.junit.After;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@CloudTestExclusionNote("This test directly creates and controls a local test service.")
@Category(RequiresLocalServer.class)
@RunWith(JUnitParamsRunner.class)
public class LoggerTest {

  private static final ListAppender<ILoggingEvent> listAppender = new ListAppender<>();

  static {
    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    ch.qos.logback.classic.Logger logger = context.getLogger(Logger.ROOT_LOGGER_NAME);
    listAppender.setContext(context);
    listAppender.start();
    logger.addAppender(listAppender);
    logger.setLevel(Level.INFO);
  }

  private static final String taskQueue = "logger-test";

  private TestWorkflowEnvironment env;

  public void setUp(String loggerTagPrefix) throws Exception {
    WorkerFactoryOptions.Builder wfob = WorkerFactoryOptions.newBuilder();
    if (!Strings.isNullOrEmpty(loggerTagPrefix)) {
      wfob.setLoggerTagPrefix(loggerTagPrefix);
    }
    env =
        TestWorkflowEnvironment.newInstance(
            TestEnvironmentOptions.newBuilder().setWorkerFactoryOptions(wfob.build()).build());
  }

  @After
  public void tearDown() throws Exception {
    env.close();
  }

  @WorkflowInterface
  public interface TestWorkflow {
    @UpdateMethod
    void update(String id);

    @WorkflowMethod
    void execute(String id);
  }

  public static class TestLoggingInWorkflow implements LoggerTest.TestWorkflow {
    private final Logger workflowLogger = Workflow.getLogger(TestLoggingInWorkflow.class);

    @Override
    public void update(String id) {
      workflowLogger.info("Updating workflow {}.", id);
    }

    @Override
    public void execute(String id) {
      workflowLogger.info("Start executing workflow {}.", id);
      ChildWorkflowOptions options =
          ChildWorkflowOptions.newBuilder().setTaskQueue(taskQueue).build();
      LoggerTest.TestChildWorkflow workflow =
          Workflow.newChildWorkflowStub(LoggerTest.TestChildWorkflow.class, options);
      workflow.executeChild(id);
      workflowLogger.info("Done executing workflow {}.", id);
    }
  }

  @WorkflowInterface
  public interface TestChildWorkflow {
    @WorkflowMethod
    void executeChild(String id);
  }

  public static class TestLoggerInChildWorkflow implements LoggerTest.TestChildWorkflow {
    private static final Logger childWorkflowLogger =
        Workflow.getLogger(TestLoggerInChildWorkflow.class);

    @Override
    public void executeChild(String id) {
      childWorkflowLogger.info("Executing child workflow {}.", id);
    }
  }

  @Test
  @Parameters({"", "temporal"})
  public void testWorkflowLogger(String ltp) throws Exception {
    setUp(ltp);
    Worker worker = env.newWorker(taskQueue);
    worker.registerWorkflowImplementationTypes(
        TestLoggingInWorkflow.class, TestLoggerInChildWorkflow.class);
    env.start();

    WorkflowClient workflowClient = env.getWorkflowClient();
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofSeconds(1000))
            .setTaskQueue(taskQueue)
            .build();
    LoggerTest.TestWorkflow workflow =
        workflowClient.newWorkflowStub(LoggerTest.TestWorkflow.class, options);
    String wfId = UUID.randomUUID().toString();
    CompletableFuture<Void> result = WorkflowClient.execute(workflow::execute, wfId);
    workflow.update(wfId);
    result.get();

    assertEquals(1, matchingLines(String.format("Start executing workflow %s.", wfId), ltp, false));
    assertEquals(1, matchingLines(String.format("Executing child workflow %s.", wfId), ltp, false));
    assertEquals(1, matchingLines(String.format("Done executing workflow %s.", wfId), ltp, false));
    // Assert the update log is present
    assertEquals(1, matchingLines(String.format("Updating workflow %s.", wfId), ltp, true));
  }

  private int matchingLines(String message, String ltp, boolean isUpdateMethod) {
    int i = 0;
    // Make copy to avoid ConcurrentModificationException
    List<ILoggingEvent> list = new ArrayList<>(listAppender.list);
    for (ILoggingEvent event : list) {
      if (event.getFormattedMessage().contains(message)) {
        assertTrue(event.getMDCPropertyMap().containsKey(ltp + LoggerTag.WORKFLOW_ID));
        assertTrue(event.getMDCPropertyMap().containsKey(ltp + LoggerTag.WORKFLOW_TYPE));
        assertTrue(event.getMDCPropertyMap().containsKey(ltp + LoggerTag.RUN_ID));
        assertTrue(event.getMDCPropertyMap().containsKey(ltp + LoggerTag.TASK_QUEUE));
        if (isUpdateMethod) {
          assertTrue(event.getMDCPropertyMap().containsKey(ltp + LoggerTag.UPDATE_ID));
          assertTrue(event.getMDCPropertyMap().containsKey(ltp + LoggerTag.UPDATE_NAME));
        }
        i++;
      }
    }
    return i;
  }
}
