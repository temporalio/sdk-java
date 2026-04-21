package io.temporal.springai.replay;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.springai.activity.ChatModelActivityImpl;
import io.temporal.springai.model.ActivityChatModel;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.worker.Worker;
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
 * Asserts that a workflow replay does not re-invoke the underlying {@link ChatModel}. The counter
 * lives on the activity's backing ChatModel, which is only reached when the {@code CallChatModel}
 * activity is scheduled by the workflow. On replay, the activity result is fetched from history;
 * the impl is not re-invoked. If we ever regress by dropping that guarantee — say by adding an
 * in-workflow cache that falls back to invoking the model directly — the counter will advance to 2
 * and this test will fail.
 */
class ChatModelSideEffectTest {

  private static final String TASK_QUEUE = "test-spring-ai-chat-side-effect";

  private TestWorkflowEnvironment testEnv;
  private WorkflowClient client;
  private CountingChatModel model;

  @BeforeEach
  void setUp() {
    testEnv = TestWorkflowEnvironment.newInstance();
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
        1, model.callCount.get(), "ChatModel should be called once during the initial run");

    WorkflowExecutionHistory history =
        client.fetchHistory(WorkflowStub.fromTyped(workflow).getExecution().getWorkflowId());
    WorkflowReplayer.replayWorkflowExecution(history, ChatWorkflowImpl.class);

    assertEquals(
        1,
        model.callCount.get(),
        "ChatModel must not be re-invoked during replay — activity results come from history");
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
