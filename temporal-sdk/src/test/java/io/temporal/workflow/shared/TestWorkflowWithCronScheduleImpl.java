package io.temporal.workflow.shared;

import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.CancellationScope;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflowWithCronSchedule;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;

public class TestWorkflowWithCronScheduleImpl implements TestWorkflowWithCronSchedule {

  public static final Map<String, AtomicInteger> retryCount = new ConcurrentHashMap<>();
  public static final Map<String, Map<Integer, String>> lastCompletionResults =
      new ConcurrentHashMap<>();
  public static Optional<Exception> lastFail;

  @Override
  public String execute(String testName) {
    Logger log = Workflow.getLogger(TestWorkflowWithCronScheduleImpl.class);

    if (CancellationScope.current().isCancelRequested()) {
      log.debug("TestWorkflowWithCronScheduleImpl run canceled.");
      return null;
    }

    lastFail = Workflow.getPreviousRunFailure();
    int count = retryCount.computeIfAbsent(testName, k -> new AtomicInteger()).incrementAndGet();
    lastCompletionResults
        .computeIfAbsent(testName, k -> new HashMap<>())
        .put(count, Workflow.getLastCompletionResult(String.class));

    if (count == 3) {
      throw ApplicationFailure.newFailure("simulated error", "test");
    }

    SimpleDateFormat sdf = new SimpleDateFormat("MMM dd,yyyy HH:mm:ss.SSS");
    Date now = new Date(Workflow.currentTimeMillis());
    log.debug("TestWorkflowWithCronScheduleImpl run at " + sdf.format(now));
    return "run " + count;
  }
}
