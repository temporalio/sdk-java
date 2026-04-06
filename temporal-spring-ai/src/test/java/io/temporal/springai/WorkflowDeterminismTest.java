package io.temporal.springai;

import static org.junit.jupiter.api.Assertions.*;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.springai.activity.ChatModelActivityImpl;
import io.temporal.springai.chat.TemporalChatClient;
import io.temporal.springai.model.ActivityChatModel;
import io.temporal.springai.tool.DeterministicTool;
import io.temporal.springai.tool.SideEffectTool;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
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
import org.springframework.ai.tool.annotation.Tool;

/**
 * Verifies that workflows using ActivityChatModel with tools execute without non-determinism
 * errors.
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
  void workflowWithChatModel_completesSuccessfully() {
    Worker worker = testEnv.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(ChatWorkflowImpl.class);

    // Register a ChatModelActivityImpl backed by a mock model that returns a canned response
    ChatModel mockModel = new StubChatModel("Hello from the model!");
    worker.registerActivitiesImplementations(new ChatModelActivityImpl(mockModel));

    testEnv.start();

    TestChatWorkflow workflow =
        client.newWorkflowStub(
            TestChatWorkflow.class, WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build());

    String result = workflow.chat("Hi");
    assertEquals("Hello from the model!", result);
  }

  @Test
  void workflowWithDeterministicTool_completesSuccessfully() {
    Worker worker = testEnv.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(ChatWithToolsWorkflowImpl.class);

    // Model returns a simple response (no tool calls)
    ChatModel mockModel = new StubChatModel("I used the tools!");
    worker.registerActivitiesImplementations(new ChatModelActivityImpl(mockModel));

    testEnv.start();

    TestChatWorkflow workflow =
        client.newWorkflowStub(
            TestChatWorkflow.class, WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build());

    String result = workflow.chat("Use tools");
    assertEquals("I used the tools!", result);
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

  // --- Stub ChatModel that returns a canned response ---

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
