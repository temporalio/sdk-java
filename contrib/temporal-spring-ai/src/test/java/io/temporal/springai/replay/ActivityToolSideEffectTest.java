package io.temporal.springai.replay;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.springai.activity.ChatModelActivityImpl;
import io.temporal.springai.chat.TemporalChatClient;
import io.temporal.springai.model.ActivityChatModel;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactoryOptions;
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
 * Asserts that {@link TemporalChatClient#defaultTools} routes activity-stub tools through the
 * activity boundary the user already set up (via {@code Workflow.newActivityStub}) rather than
 * invoking the underlying impl directly from workflow code. This is a plugin property: the plugin
 * must recognize the stub as an activity-backed tool and invoke it as such, not unwrap it into a
 * plain in-workflow Java method call. Temporal's replay semantics for activities are assumed
 * correct.
 *
 * <p>We verify by scanning history for {@code ActivityTaskScheduled} events. Counting invocations
 * of the backing activity impl would conflate two different signals: the plugin inlining the tool
 * call (what we want to catch) vs. Temporal re-delivering the activity task to the worker (which
 * can legitimately happen with {@code maxAttempts > 1}).
 */
class ActivityToolSideEffectTest {

  private static final String TASK_QUEUE = "test-spring-ai-activity-tool-side-effect";

  private TestWorkflowEnvironment testEnv;
  private WorkflowClient client;

  @BeforeEach
  void setUp() {
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
  void activityTool_schedulesExactlyOneActivity() {
    Worker worker = testEnv.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(ChatWithToolsWorkflowImpl.class);
    worker.registerActivitiesImplementations(
        new ChatModelActivityImpl(new ToolCallingStubChatModel()), new AddActivityImpl());
    testEnv.start();

    ChatWithToolsWorkflow workflow =
        client.newWorkflowStub(
            ChatWithToolsWorkflow.class,
            WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build());
    assertEquals("The answer is 5", workflow.chat("What is 2+3?"));

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
                    "Add"
                        .equals(
                            e.getActivityTaskScheduledEventAttributes()
                                .getActivityType()
                                .getName()))
            .count();
    assertEquals(
        1,
        scheduled,
        "TemporalChatClient must invoke activity-stub tools as activities; expected exactly one"
            + " Add ActivityTaskScheduled event");
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
    @Override
    public int add(int a, int b) {
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
