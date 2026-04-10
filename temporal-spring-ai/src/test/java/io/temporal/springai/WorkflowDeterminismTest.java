package io.temporal.springai;

import static org.junit.jupiter.api.Assertions.*;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.springai.activity.ChatModelActivityImpl;
import io.temporal.springai.chat.TemporalChatClient;
import io.temporal.springai.model.ActivityChatModel;
import io.temporal.springai.tool.DeterministicTool;
import io.temporal.springai.tool.SideEffectTool;
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
import org.springframework.ai.tool.annotation.Tool;

/**
 * Verifies that workflows using ActivityChatModel with tools are deterministic by running them to
 * completion and then replaying from the captured history.
 */
class WorkflowDeterminismTest {

  private static final String TASK_QUEUE = "test-spring-ai";

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
  void workflowWithChatModel_replaysDeterministically() throws Exception {
    Worker worker = testEnv.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(ChatWorkflowImpl.class);
    worker.registerActivitiesImplementations(
        new ChatModelActivityImpl(new StubChatModel("Hello from the model!")));

    testEnv.start();

    TestChatWorkflow workflow =
        client.newWorkflowStub(
            TestChatWorkflow.class, WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build());

    String result = workflow.chat("Hi");
    assertEquals("Hello from the model!", result);

    WorkflowExecutionHistory history =
        client.fetchHistory(WorkflowStub.fromTyped(workflow).getExecution().getWorkflowId());
    WorkflowReplayer.replayWorkflowExecution(history, ChatWorkflowImpl.class);
  }

  @Test
  void workflowWithTools_replaysDeterministically() throws Exception {
    Worker worker = testEnv.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(ChatWithToolsWorkflowImpl.class);

    // First call: model requests the "add" tool. Second call: model returns final text.
    ChatModel toolCallingModel = new ToolCallingStubChatModel();
    worker.registerActivitiesImplementations(new ChatModelActivityImpl(toolCallingModel));

    testEnv.start();

    TestChatWorkflow workflow =
        client.newWorkflowStub(
            TestChatWorkflow.class, WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build());

    String result = workflow.chat("What is 2+3?");
    assertEquals("The answer is 5", result);

    WorkflowExecutionHistory history =
        client.fetchHistory(WorkflowStub.fromTyped(workflow).getExecution().getWorkflowId());
    WorkflowReplayer.replayWorkflowExecution(history, ChatWithToolsWorkflowImpl.class);
  }

  // --- Workflow interfaces and implementations ---

  @WorkflowInterface
  public interface TestChatWorkflow {
    @WorkflowMethod
    String chat(String message);
  }

  public static class ChatWorkflowImpl implements TestChatWorkflow {
    @Override
    public String chat(String message) {
      ActivityChatModel chatModel = ActivityChatModel.forDefault();
      ChatClient chatClient = TemporalChatClient.builder(chatModel).build();
      return chatClient.prompt().user(message).call().content();
    }
  }

  public static class ChatWithToolsWorkflowImpl implements TestChatWorkflow {
    @Override
    public String chat(String message) {
      ActivityChatModel chatModel = ActivityChatModel.forDefault();
      TestDeterministicTools deterministicTools = new TestDeterministicTools();
      TestSideEffectTools sideEffectTools = new TestSideEffectTools();
      ChatClient chatClient =
          TemporalChatClient.builder(chatModel)
              .defaultTools(deterministicTools, sideEffectTools)
              .build();
      return chatClient.prompt().user(message).call().content();
    }
  }

  // --- Test tool classes ---

  @DeterministicTool
  public static class TestDeterministicTools {
    @Tool(description = "Add two numbers")
    public int add(int a, int b) {
      return a + b;
    }
  }

  @SideEffectTool
  public static class TestSideEffectTools {
    @Tool(description = "Get a timestamp")
    public String timestamp() {
      return "2025-01-01T00:00:00Z";
    }
  }

  // --- Stub ChatModels ---

  /** Always returns a final text response, no tool calls. */
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

  /**
   * First call: returns a tool call request for "add(2, 3)". Second call (after tool response):
   * returns final text "The answer is 5".
   */
  private static class ToolCallingStubChatModel implements ChatModel {
    private final AtomicInteger callCount = new AtomicInteger(0);

    @Override
    public ChatResponse call(Prompt prompt) {
      if (callCount.getAndIncrement() == 0) {
        // First call: request a tool call
        AssistantMessage toolRequest =
            AssistantMessage.builder()
                .content("")
                .toolCalls(
                    List.of(
                        new AssistantMessage.ToolCall(
                            "call_1", "function", "add", "{\"a\":2,\"b\":3}")))
                .build();
        return ChatResponse.builder().generations(List.of(new Generation(toolRequest))).build();
      } else {
        // Second call: return final response
        return ChatResponse.builder()
            .generations(List.of(new Generation(new AssistantMessage("The answer is 5"))))
            .build();
      }
    }

    @Override
    public reactor.core.publisher.Flux<ChatResponse> stream(Prompt prompt) {
      throw new UnsupportedOperationException();
    }
  }
}
