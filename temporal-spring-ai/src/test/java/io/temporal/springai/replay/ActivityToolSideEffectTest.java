package io.temporal.springai.replay;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.springai.activity.ChatModelActivityImpl;
import io.temporal.springai.chat.TemporalChatClient;
import io.temporal.springai.model.ActivityChatModel;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.worker.Worker;
import io.temporal.workflow.Workflow;
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
import org.springframework.ai.tool.annotation.Tool;

/**
 * Asserts that a workflow replay does not re-invoke activity-backed tools. The {@link AddActivity}
 * impl holds a counter that increments on each tool call. After the initial run the counter is 1;
 * after replaying the captured history, it must still be 1 — activity results for the tool call
 * come from history, not from re-invoking the activity impl.
 */
class ActivityToolSideEffectTest {

  private static final String TASK_QUEUE = "test-spring-ai-activity-tool-side-effect";

  private TestWorkflowEnvironment testEnv;
  private WorkflowClient client;
  private AddActivityImpl addActivity;

  @BeforeEach
  void setUp() {
    testEnv = TestWorkflowEnvironment.newInstance();
    client = testEnv.getWorkflowClient();
    addActivity = new AddActivityImpl();
  }

  @AfterEach
  void tearDown() {
    testEnv.close();
  }

  @Test
  void activityTool_notReInvokedOnReplay() throws Exception {
    Worker worker = testEnv.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(ChatWithToolsWorkflowImpl.class);
    worker.registerActivitiesImplementations(
        new ChatModelActivityImpl(new ToolCallingStubChatModel()), addActivity);
    testEnv.start();

    ChatWithToolsWorkflow workflow =
        client.newWorkflowStub(
            ChatWithToolsWorkflow.class,
            WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build());
    assertEquals("The answer is 5", workflow.chat("What is 2+3?"));
    assertEquals(
        1, addActivity.callCount.get(), "Tool activity should run once during the initial run");

    WorkflowExecutionHistory history =
        client.fetchHistory(WorkflowStub.fromTyped(workflow).getExecution().getWorkflowId());
    WorkflowReplayer.replayWorkflowExecution(history, ChatWithToolsWorkflowImpl.class);

    assertEquals(
        1,
        addActivity.callCount.get(),
        "Tool activity must not be re-invoked during replay — results come from history");
  }

  @WorkflowInterface
  public interface ChatWithToolsWorkflow {
    @WorkflowMethod
    String chat(String message);
  }

  @ActivityInterface
  public interface AddActivity {
    @Tool(description = "Add two numbers")
    @ActivityMethod
    int add(int a, int b);
  }

  public static class AddActivityImpl implements AddActivity {
    final AtomicInteger callCount = new AtomicInteger(0);

    @Override
    public int add(int a, int b) {
      callCount.incrementAndGet();
      return a + b;
    }
  }

  public static class ChatWithToolsWorkflowImpl implements ChatWithToolsWorkflow {
    @Override
    public String chat(String message) {
      ActivityChatModel chatModel = ActivityChatModel.forDefault();
      AddActivity addTool =
          Workflow.newActivityStub(
              AddActivity.class,
              ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(30)).build());
      ChatClient chatClient = TemporalChatClient.builder(chatModel).defaultTools(addTool).build();
      return chatClient.prompt().user(message).call().content();
    }
  }

  /** First call: request the "add" tool. Second call: return final text. */
  private static class ToolCallingStubChatModel implements ChatModel {
    private final AtomicInteger callCount = new AtomicInteger(0);

    @Override
    public ChatResponse call(Prompt prompt) {
      if (callCount.getAndIncrement() == 0) {
        AssistantMessage toolRequest =
            AssistantMessage.builder()
                .content("")
                .toolCalls(
                    List.of(
                        new AssistantMessage.ToolCall(
                            "call_1", "function", "add", "{\"a\":2,\"b\":3}")))
                .build();
        return ChatResponse.builder().generations(List.of(new Generation(toolRequest))).build();
      }
      return ChatResponse.builder()
          .generations(List.of(new Generation(new AssistantMessage("The answer is 5"))))
          .build();
    }

    @Override
    public reactor.core.publisher.Flux<ChatResponse> stream(Prompt prompt) {
      throw new UnsupportedOperationException();
    }
  }
}
