package io.temporal.springai.replay;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.springai.activity.ChatModelActivityImpl;
import io.temporal.springai.chat.TemporalChatClient;
import io.temporal.springai.model.ActivityChatModel;
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
 * Asserts that {@code Workflow.sideEffect(...)} memoization works for {@link SideEffectTool}
 * bodies. Unlike activity-backed tools, the {@code SideEffectToolCallback} wrapper runs on the
 * workflow side, so <em>every</em> workflow statement re-runs during replay — including the call
 * into {@code SideEffectToolCallback.call()}. What must NOT re-run is the inner tool body (the
 * lambda passed to {@code Workflow.sideEffect}); that result is fetched from the recorded marker.
 *
 * <p>A regression that dropped the sideEffect wrapper (e.g., directly invoking the delegate) would
 * bump this counter to 2 on replay.
 */
class SideEffectToolReplayTest {

  private static final String TASK_QUEUE = "test-spring-ai-side-effect-tool";

  /** Lives at file scope so the counter is visible from workflow code via static reference. */
  static final AtomicInteger CALL_COUNT = new AtomicInteger(0);

  private TestWorkflowEnvironment testEnv;
  private WorkflowClient client;

  @BeforeEach
  void setUp() {
    CALL_COUNT.set(0);
    testEnv = TestWorkflowEnvironment.newInstance();
    client = testEnv.getWorkflowClient();
  }

  @AfterEach
  void tearDown() {
    testEnv.close();
  }

  @Test
  void sideEffectTool_notReInvokedOnReplay() throws Exception {
    Worker worker = testEnv.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(TimestampWorkflowImpl.class);
    worker.registerActivitiesImplementations(
        new ChatModelActivityImpl(new ToolCallingStubChatModel()));
    testEnv.start();

    TimestampWorkflow workflow =
        client.newWorkflowStub(
            TimestampWorkflow.class, WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build());
    assertEquals("got: 2026-04-21T00:00:00Z", workflow.chat("what time is it?"));
    assertEquals(
        1, CALL_COUNT.get(), "@SideEffectTool body should run once during the initial run");

    WorkflowExecutionHistory history =
        client.fetchHistory(WorkflowStub.fromTyped(workflow).getExecution().getWorkflowId());
    WorkflowReplayer.replayWorkflowExecution(history, TimestampWorkflowImpl.class);

    assertEquals(
        1,
        CALL_COUNT.get(),
        "@SideEffectTool body must not re-run on replay — result comes from the recorded"
            + " sideEffect marker");
  }

  @WorkflowInterface
  public interface TimestampWorkflow {
    @WorkflowMethod
    String chat(String message);
  }

  @SideEffectTool
  public static class CountingTimestampTool {
    @Tool(description = "Get the current timestamp")
    public String now() {
      CALL_COUNT.incrementAndGet();
      return "2026-04-21T00:00:00Z";
    }
  }

  public static class TimestampWorkflowImpl implements TimestampWorkflow {
    @Override
    public String chat(String message) {
      ActivityChatModel chatModel = ActivityChatModel.forDefault();
      ChatClient chatClient =
          TemporalChatClient.builder(chatModel).defaultTools(new CountingTimestampTool()).build();
      return chatClient.prompt().user(message).call().content();
    }
  }

  /** First call: request the "now" tool. Second call: return final text with the timestamp. */
  private static class ToolCallingStubChatModel implements ChatModel {
    private final AtomicInteger callCount = new AtomicInteger(0);

    @Override
    public ChatResponse call(Prompt prompt) {
      if (callCount.getAndIncrement() == 0) {
        AssistantMessage toolRequest =
            AssistantMessage.builder()
                .content("")
                .toolCalls(
                    List.of(new AssistantMessage.ToolCall("call_1", "function", "now", "{}")))
                .build();
        return ChatResponse.builder().generations(List.of(new Generation(toolRequest))).build();
      }
      return ChatResponse.builder()
          .generations(List.of(new Generation(new AssistantMessage("got: 2026-04-21T00:00:00Z"))))
          .build();
    }

    @Override
    public reactor.core.publisher.Flux<ChatResponse> stream(Prompt prompt) {
      throw new UnsupportedOperationException();
    }
  }
}
