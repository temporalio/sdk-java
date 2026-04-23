package io.temporal.springai.replay;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.springai.activity.ChatModelActivityImpl;
import io.temporal.springai.model.ActivityChatModel;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
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

/**
 * Asserts that {@link ActivityChatModel} routes every {@link ChatModel} invocation through an
 * activity boundary rather than calling the underlying {@code ChatModel} directly from workflow
 * code. This is a property of the plugin, not of Temporal: Temporal guarantees activity results are
 * replayed from history, but that only helps if the plugin actually scheduled an activity in the
 * first place.
 *
 * <p>Concretely, if a regression routed chat calls directly (e.g. an in-workflow cache whose miss
 * path invokes the {@code ChatModel} inline), replay would re-run that inline call and the counter
 * would advance past 1. Caching is disabled in {@link #setUp()} so the worker replays on every
 * workflow task — making this failure mode observable during the initial run, not only via the
 * explicit {@code WorkflowReplayer} pass.
 */
class ChatModelSideEffectTest {

  private static final String TASK_QUEUE = "test-spring-ai-chat-side-effect";

  private TestWorkflowEnvironment testEnv;
  private WorkflowClient client;
  private CountingChatModel model;

  @BeforeEach
  void setUp() {
    // WorkflowCacheSize(0) forces the worker to replay from history on every workflow task
    // instead of resuming from in-memory cached state, which is what we actually need to
    // assert side-effect safety: any un-wrapped side effect in workflow code would run on
    // each replay and bump the counter.
    testEnv =
        TestWorkflowEnvironment.newInstance(
            TestEnvironmentOptions.newBuilder()
                .setWorkerFactoryOptions(
                    WorkerFactoryOptions.newBuilder().setWorkflowCacheSize(0).build())
                .build());
    client = testEnv.getWorkflowClient();
    model = new CountingChatModel("pong");
  }

  @AfterEach
  void tearDown() {
    testEnv.close();
  }

  @Test
  void chatModel_notReInvokedOnReplay() throws Exception {
    Worker worker = testEnv.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(ChatWorkflowImpl.class);
    worker.registerActivitiesImplementations(new ChatModelActivityImpl(model));
    testEnv.start();

    ChatWorkflow workflow =
        client.newWorkflowStub(
            ChatWorkflow.class, WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build());
    assertEquals("pong", workflow.chat("ping"));
    assertEquals(
        1,
        model.callCount.get(),
        "sanity check: the ChatModel ran exactly once for one workflow invocation");

    WorkflowExecutionHistory history =
        client.fetchHistory(WorkflowStub.fromTyped(workflow).getExecution().getWorkflowId());
    WorkflowReplayer.replayWorkflowExecution(history, ChatWorkflowImpl.class);

    assertEquals(
        1,
        model.callCount.get(),
        "ActivityChatModel must place ChatModel calls behind an activity boundary; a counter"
            + " above 1 means the plugin invoked the ChatModel directly from workflow code"
            + " and replay re-ran it");
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

  private static class CountingChatModel implements ChatModel {
    final AtomicInteger callCount = new AtomicInteger(0);
    private final String response;

    CountingChatModel(String response) {
      this.response = response;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
      callCount.incrementAndGet();
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
