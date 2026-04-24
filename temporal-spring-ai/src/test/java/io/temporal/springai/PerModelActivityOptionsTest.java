package io.temporal.springai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.temporal.activity.ActivityOptions;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.springai.activity.ChatModelActivityImpl;
import io.temporal.springai.chat.TemporalChatClient;
import io.temporal.springai.model.ActivityChatModel;
import io.temporal.springai.model.ChatModelTypes;
import io.temporal.springai.plugin.SpringAiPluginOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * Verifies that the per-model {@link ActivityOptions} registry installed by {@link
 * io.temporal.springai.plugin.SpringAiPlugin} is consulted by {@link ActivityChatModel#forModel} /
 * {@link ActivityChatModel#forDefault}, and that explicit {@code ActivityOptions} bypass it.
 */
class PerModelActivityOptionsTest {

  private static final String TASK_QUEUE = "test-spring-ai-per-model-options";
  private static final Duration SLOW_TIMEOUT = Duration.ofMinutes(10);
  private static final Duration EXPLICIT_TIMEOUT = Duration.ofMinutes(7);
  private static final Duration CATCH_ALL_TIMEOUT = Duration.ofMinutes(4);
  private static final Duration DEFAULT_TIMEOUT = ActivityChatModel.DEFAULT_TIMEOUT;

  private TestWorkflowEnvironment testEnv;
  private WorkflowClient client;

  @BeforeEach
  void setUp() {
    // Defensive: the registry is a JVM-wide singleton; start each test with a known empty state.
    SpringAiPluginOptions.register(Map.of());
    testEnv = TestWorkflowEnvironment.newInstance();
    client = testEnv.getWorkflowClient();
  }

  @AfterEach
  void tearDown() {
    // Keep the static registry from leaking between tests.
    SpringAiPluginOptions.register(Map.of());
    testEnv.close();
  }

  @Test
  void registryHit_usesPerModelOptions() {
    SpringAiPluginOptions.register(
        Map.of(
            "slow",
            ActivityOptions.newBuilder(ActivityChatModel.defaultActivityOptions())
                .setStartToCloseTimeout(SLOW_TIMEOUT)
                .build()));

    Worker worker = testEnv.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(SlowModelWorkflowImpl.class);
    worker.registerActivitiesImplementations(stubActivityImpl());
    testEnv.start();

    runAndAssertScheduledTimeout(SlowModelWorkflow.class, SLOW_TIMEOUT);
  }

  @Test
  void registryMiss_fallsBackToDefault() {
    // Populate the registry with an *unrelated* entry so we're sure the lookup ran and missed.
    SpringAiPluginOptions.register(
        Map.of(
            "some-other-model",
            ActivityOptions.newBuilder(ActivityChatModel.defaultActivityOptions())
                .setStartToCloseTimeout(SLOW_TIMEOUT)
                .build()));

    Worker worker = testEnv.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(UnknownModelWorkflowImpl.class);
    worker.registerActivitiesImplementations(stubActivityImpl());
    testEnv.start();

    runAndAssertScheduledTimeout(UnknownModelWorkflow.class, DEFAULT_TIMEOUT);
  }

  @Test
  void catchAllKey_appliesToUnknownModel() {
    // Only the DEFAULT_MODEL_NAME catch-all is registered; a lookup for a model without its own
    // entry should fall through to it (not to the library defaults).
    SpringAiPluginOptions.register(
        Map.of(
            ChatModelTypes.DEFAULT_MODEL_NAME,
            ActivityOptions.newBuilder(ActivityChatModel.defaultActivityOptions())
                .setStartToCloseTimeout(CATCH_ALL_TIMEOUT)
                .build()));

    Worker worker = testEnv.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(UnknownModelWorkflowImpl.class);
    worker.registerActivitiesImplementations(stubActivityImpl());
    testEnv.start();

    runAndAssertScheduledTimeout(UnknownModelWorkflow.class, CATCH_ALL_TIMEOUT);
  }

  @Test
  void specificEntry_winsOverCatchAll() {
    // Both the catch-all and a model-specific entry are registered; the model-specific one
    // must win for that model.
    SpringAiPluginOptions.register(
        Map.of(
            ChatModelTypes.DEFAULT_MODEL_NAME,
            ActivityOptions.newBuilder(ActivityChatModel.defaultActivityOptions())
                .setStartToCloseTimeout(CATCH_ALL_TIMEOUT)
                .build(),
            "slow",
            ActivityOptions.newBuilder(ActivityChatModel.defaultActivityOptions())
                .setStartToCloseTimeout(SLOW_TIMEOUT)
                .build()));

    Worker worker = testEnv.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(SlowModelWorkflowImpl.class);
    worker.registerActivitiesImplementations(stubActivityImpl());
    testEnv.start();

    runAndAssertScheduledTimeout(SlowModelWorkflow.class, SLOW_TIMEOUT);
  }

  @Test
  void explicitOptions_bypassRegistry() {
    SpringAiPluginOptions.register(
        Map.of(
            "slow",
            ActivityOptions.newBuilder(ActivityChatModel.defaultActivityOptions())
                .setStartToCloseTimeout(SLOW_TIMEOUT)
                .build()));

    Worker worker = testEnv.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(ExplicitOptionsWorkflowImpl.class);
    worker.registerActivitiesImplementations(stubActivityImpl());
    testEnv.start();

    runAndAssertScheduledTimeout(ExplicitOptionsWorkflow.class, EXPLICIT_TIMEOUT);
  }

  /**
   * A ChatModelActivityImpl that resolves every model name we reference across the tests — the
   * tests care about how options are looked up, not which ChatModel gets called.
   */
  private static ChatModelActivityImpl stubActivityImpl() {
    StubChatModel stub = new StubChatModel();
    return new ChatModelActivityImpl(
        Map.of("default", stub, "slow", stub, "not-in-registry", stub), "default");
  }

  private <W extends ChatOnceWorkflow> void runAndAssertScheduledTimeout(
      Class<W> workflowInterface, Duration expected) {
    ChatOnceWorkflow workflow =
        client.newWorkflowStub(
            workflowInterface, WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build());
    workflow.chat("ping");

    String workflowId = WorkflowStub.fromTyped(workflow).getExecution().getWorkflowId();
    List<HistoryEvent> events = client.fetchHistory(workflowId).getHistory().getEventsList();
    Duration scheduled = findScheduledStartToClose(events);
    assertEquals(expected, scheduled);
  }

  private static Duration findScheduledStartToClose(List<HistoryEvent> events) {
    for (HistoryEvent e : events) {
      if (!e.hasActivityTaskScheduledEventAttributes()) continue;
      var attrs = e.getActivityTaskScheduledEventAttributes();
      if ("CallChatModel".equals(attrs.getActivityType().getName())) {
        var pd = attrs.getStartToCloseTimeout();
        return Duration.ofSeconds(pd.getSeconds(), pd.getNanos());
      }
    }
    return null;
  }

  public interface ChatOnceWorkflow {
    String chat(String message);
  }

  @WorkflowInterface
  public interface SlowModelWorkflow extends ChatOnceWorkflow {
    @Override
    @WorkflowMethod
    String chat(String message);
  }

  @WorkflowInterface
  public interface UnknownModelWorkflow extends ChatOnceWorkflow {
    @Override
    @WorkflowMethod
    String chat(String message);
  }

  @WorkflowInterface
  public interface ExplicitOptionsWorkflow extends ChatOnceWorkflow {
    @Override
    @WorkflowMethod
    String chat(String message);
  }

  public static class SlowModelWorkflowImpl implements SlowModelWorkflow {
    @Override
    public String chat(String message) {
      return TemporalChatClient.builder(ActivityChatModel.forModel("slow"))
          .build()
          .prompt()
          .user(message)
          .call()
          .content();
    }
  }

  public static class UnknownModelWorkflowImpl implements UnknownModelWorkflow {
    @Override
    public String chat(String message) {
      return TemporalChatClient.builder(ActivityChatModel.forModel("not-in-registry"))
          .build()
          .prompt()
          .user(message)
          .call()
          .content();
    }
  }

  public static class ExplicitOptionsWorkflowImpl implements ExplicitOptionsWorkflow {
    @Override
    public String chat(String message) {
      ActivityOptions explicit =
          ActivityOptions.newBuilder(ActivityChatModel.defaultActivityOptions())
              .setStartToCloseTimeout(EXPLICIT_TIMEOUT)
              .build();
      // Registry entry "slow" is set but this call passes its own ActivityOptions, so the
      // registry should be ignored.
      return TemporalChatClient.builder(ActivityChatModel.forModel("slow", explicit))
          .build()
          .prompt()
          .user(message)
          .call()
          .content();
    }
  }

  private static class StubChatModel implements ChatModel {
    @Override
    public ChatResponse call(Prompt prompt) {
      return ChatResponse.builder()
          .generations(List.of(new Generation(new AssistantMessage("pong"))))
          .build();
    }

    @Override
    public reactor.core.publisher.Flux<ChatResponse> stream(Prompt prompt) {
      throw new UnsupportedOperationException();
    }
  }
}
