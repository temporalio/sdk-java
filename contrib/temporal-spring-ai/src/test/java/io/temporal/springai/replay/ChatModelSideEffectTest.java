package io.temporal.springai.replay;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.springai.activity.ChatModelActivityImpl;
import io.temporal.springai.model.ActivityChatModel;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * Asserts that {@link ActivityChatModel} routes every {@link ChatModel} invocation through an
 * activity boundary rather than calling the underlying {@code ChatModel} directly from workflow
 * code. This is a property of the plugin, not of Temporal: Temporal guarantees activity results are
 * replayed from history, but that only helps if the plugin actually scheduled an activity in the
 * first place.
 *
 * <p>We verify by scanning history for {@code ActivityTaskScheduled} events. Counting invocations
 * of the backing {@code ChatModel} would conflate two different signals: the plugin inlining the
 * call (what we want to catch) vs. Temporal re-delivering an activity task to the worker (which can
 * legitimately happen with {@code maxAttempts > 1}). The scheduled-event count is invariant under
 * activity-task redelivery.
 */
class ChatModelSideEffectTest {

  private static final String TASK_QUEUE = "test-spring-ai-chat-side-effect";

  private TestWorkflowEnvironment testEnv;
  private WorkflowClient client;

  @BeforeEach
  void setUp() {
    // WorkflowCacheSize(0) forces the worker to replay from history on every workflow task
    // instead of resuming from in-memory cached state, which is what we actually need to
    // assert side-effect safety: any un-wrapped side effect in workflow code would run on
    // each replay.
    testEnv =
        TestWorkflowEnvironment.newInstance(
            TestEnvironmentOptions.newBuilder()
                .setWorkerFactoryOptions(
                    WorkerFactoryOptions.newBuilder().setWorkflowCacheSize(0).build())
                .build());
    client = testEnv.getWorkflowClient();
  }

  @AfterEach
  void tearDown() {
    testEnv.close();
  }

  @Test
  void chatCall_schedulesExactlyOneActivity() {
    Worker worker = testEnv.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(ChatWorkflowImpl.class);
    worker.registerActivitiesImplementations(new ChatModelActivityImpl(new StubChatModel("pong")));
    testEnv.start();

    ChatWorkflow workflow =
        client.newWorkflowStub(
            ChatWorkflow.class, WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build());
    assertEquals("pong", workflow.chat("ping"));

    List<HistoryEvent> events =
        client
            .fetchHistory(WorkflowStub.fromTyped(workflow).getExecution().getWorkflowId())
            .getHistory()
            .getEventsList();
    long scheduled =
        events.stream()
            .filter(HistoryEvent::hasActivityTaskScheduledEventAttributes)
            .filter(
                e ->
                    "CallChatModel"
                        .equals(
                            e.getActivityTaskScheduledEventAttributes()
                                .getActivityType()
                                .getName()))
            .count();
    assertEquals(
        1,
        scheduled,
        "ActivityChatModel must place ChatModel calls behind an activity boundary; expected"
            + " exactly one CallChatModel ActivityTaskScheduled event");
  }

  @WorkflowInterface
  public interface ChatWorkflow {
    @WorkflowMethod
    String chat(String message);
  }

  public static class ChatWorkflowImpl implements ChatWorkflow {
    @Override
    public String chat(String message) {
      ActivityChatModel chatModel = ActivityChatModel.forDefault();
      ChatClient chatClient = ChatClient.builder(chatModel).build();
      return chatClient.prompt().user(message).call().content();
    }
  }

  private static class StubChatModel implements ChatModel {
    private final String response;

    StubChatModel(String response) {
      this.response = response;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
      return ChatResponse.builder()
          .generations(List.of(new Generation(new AssistantMessage(response))))
          .build();
    }

    @Override
    public reactor.core.publisher.Flux<ChatResponse> stream(Prompt prompt) {
      throw new UnsupportedOperationException();
    }
  }
}
