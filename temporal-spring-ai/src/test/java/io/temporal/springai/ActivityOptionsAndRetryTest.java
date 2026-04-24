package io.temporal.springai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.temporal.activity.ActivityOptions;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.RetryOptions;
import io.temporal.springai.activity.ChatModelActivityImpl;
import io.temporal.springai.chat.TemporalChatClient;
import io.temporal.springai.model.ActivityChatModel;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.retry.NonTransientAiException;

/**
 * Verifies the retry-classification and custom-{@link ActivityOptions} surface added by the
 * retry-and-options branch.
 */
class ActivityOptionsAndRetryTest {

  private static final String TASK_QUEUE = "test-spring-ai-retry";
  private static final String CUSTOM_TASK_QUEUE = "test-spring-ai-custom-queue";

  private TestWorkflowEnvironment testEnv;
  private WorkflowClient client;

  @BeforeEach
  void setUp() {
    testEnv = TestWorkflowEnvironment.newInstance();
    client = testEnv.getWorkflowClient();
  }

  @AfterEach
  void tearDown() {
    testEnv.close();
  }

  @Test
  void defaultFactory_marksNonTransientAiExceptionNonRetryable() {
    Worker worker = testEnv.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(ChatWorkflowImpl.class);
    CountingChatModel model = new CountingChatModel(new NonTransientAiException("bad key"));
    worker.registerActivitiesImplementations(new ChatModelActivityImpl(model));
    testEnv.start();

    ChatWorkflow workflow =
        client.newWorkflowStub(
            ChatWorkflow.class, WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build());

    try {
      workflow.chat("hello");
    } catch (WorkflowException expected) {
      // Expected — the workflow propagates the non-retryable failure.
    }

    assertEquals(
        1,
        model.callCount.get(),
        "NonTransientAiException must not be retried by the default policy");
  }

  @Test
  void defaultFactory_retriesTransientExceptions() {
    Worker worker = testEnv.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(ChatWorkflowImpl.class);
    // First 2 calls throw a plain RuntimeException (transient to Temporal); 3rd succeeds.
    CountingChatModel model =
        new CountingChatModel(new RuntimeException("flaky"), new RuntimeException("flaky"), null);
    worker.registerActivitiesImplementations(new ChatModelActivityImpl(model));
    testEnv.start();

    ChatWorkflow workflow =
        client.newWorkflowStub(
            ChatWorkflow.class, WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build());

    String result = workflow.chat("hello");
    assertEquals("ok", result);
    assertEquals(
        3, model.callCount.get(), "Transient RuntimeException should be retried up to 3 attempts");
  }

  @Test
  void customOptions_landOnScheduledActivity() {
    // Worker on the default task queue runs the workflow. A second worker on the *custom* task
    // queue runs the chat activity — the chat stub's task queue override is what routes there.
    Worker workflowWorker = testEnv.newWorker(TASK_QUEUE);
    workflowWorker.registerWorkflowImplementationTypes(CustomQueueChatWorkflowImpl.class);

    Worker activityWorker = testEnv.newWorker(CUSTOM_TASK_QUEUE);
    activityWorker.registerActivitiesImplementations(
        new ChatModelActivityImpl(new FixedChatModel("routed")));
    testEnv.start();

    ChatWorkflow workflow =
        client.newWorkflowStub(
            ChatWorkflow.class, WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build());
    assertEquals("routed", workflow.chat("hi"));

    String workflowId = WorkflowStub.fromTyped(workflow).getExecution().getWorkflowId();
    List<HistoryEvent> events = client.fetchHistory(workflowId).getHistory().getEventsList();
    String scheduledOn = findScheduledActivityTaskQueue(events);
    assertTrue(
        CUSTOM_TASK_QUEUE.equals(scheduledOn),
        "callChatModel activity should have been scheduled on the custom task queue, but was: "
            + scheduledOn);
  }

  private static String findScheduledActivityTaskQueue(List<HistoryEvent> events) {
    for (HistoryEvent e : events) {
      if (!e.hasActivityTaskScheduledEventAttributes()) continue;
      var attrs = e.getActivityTaskScheduledEventAttributes();
      if ("CallChatModel".equals(attrs.getActivityType().getName())) {
        return attrs.getTaskQueue().getName();
      }
    }
    return null;
  }

  @WorkflowInterface
  public interface ChatWorkflow {
    @WorkflowMethod
    String chat(String message);
  }

  public static class ChatWorkflowImpl implements ChatWorkflow {
    @Override
    public String chat(String message) {
      ActivityChatModel model = ActivityChatModel.forDefault();
      ChatClient chat = TemporalChatClient.builder(model).build();
      return chat.prompt().user(message).call().content();
    }
  }

  public static class CustomQueueChatWorkflowImpl implements ChatWorkflow {
    @Override
    public String chat(String message) {
      ActivityOptions opts =
          ActivityOptions.newBuilder(ActivityChatModel.defaultActivityOptions())
              .setTaskQueue(CUSTOM_TASK_QUEUE)
              .setRetryOptions(
                  RetryOptions.newBuilder()
                      .setMaximumAttempts(1)
                      .build()) // keep this test fast — don't retry on surprise failures
              .setStartToCloseTimeout(Duration.ofSeconds(30))
              .build();
      ActivityChatModel model = ActivityChatModel.forDefault(opts);
      ChatClient chat = TemporalChatClient.builder(model).build();
      return chat.prompt().user(message).call().content();
    }
  }

  /**
   * ChatModel whose behavior is scripted by a queue of outcomes. Each outcome is either a Throwable
   * (thrown on that call) or null (return "ok"). Extra calls after the queue empties replay the
   * last outcome.
   */
  private static class CountingChatModel implements ChatModel {
    final AtomicInteger callCount = new AtomicInteger(0);
    private final Object[] outcomes;

    CountingChatModel(Object... outcomes) {
      this.outcomes = outcomes;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
      int i = callCount.getAndIncrement();
      Object outcome = outcomes[Math.min(i, outcomes.length - 1)];
      if (outcome instanceof RuntimeException re) {
        throw re;
      }
      return ChatResponse.builder()
          .generations(List.of(new Generation(new AssistantMessage("ok"))))
          .build();
    }

    @Override
    public reactor.core.publisher.Flux<ChatResponse> stream(Prompt prompt) {
      throw new UnsupportedOperationException();
    }
  }

  private static class FixedChatModel implements ChatModel {
    private final String content;

    FixedChatModel(String content) {
      this.content = content;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
      return ChatResponse.builder()
          .generations(List.of(new Generation(new AssistantMessage(content))))
          .build();
    }

    @Override
    public reactor.core.publisher.Flux<ChatResponse> stream(Prompt prompt) {
      throw new UnsupportedOperationException();
    }
  }
}
