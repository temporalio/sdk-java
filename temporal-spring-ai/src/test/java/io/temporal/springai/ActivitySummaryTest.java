package io.temporal.springai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.springai.activity.ChatModelActivityImpl;
import io.temporal.springai.chat.TemporalChatClient;
import io.temporal.springai.model.ActivityChatModel;
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

/**
 * Verifies that activity calls scheduled by the Spring AI plugin carry a human-readable Summary in
 * their {@code userMetadata} so the Temporal UI can label them meaningfully.
 */
class ActivitySummaryTest {

  private static final String TASK_QUEUE = "test-spring-ai-summary";

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
  void chatActivity_carriesModelOnlySummary_neverLeaksUserPrompt() {
    Worker worker = testEnv.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(ChatWorkflowImpl.class);
    worker.registerActivitiesImplementations(
        new ChatModelActivityImpl(new StubChatModel("Hello!")));
    testEnv.start();

    SummaryTestWorkflow workflow =
        client.newWorkflowStub(
            SummaryTestWorkflow.class,
            WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build());
    String prompt = "What is the capital of France?";
    workflow.chat(prompt);

    String workflowId = WorkflowStub.fromTyped(workflow).getExecution().getWorkflowId();
    List<HistoryEvent> events = client.fetchHistory(workflowId).getHistory().getEventsList();

    String summary = findChatActivitySummary(events);
    assertNotNull(summary, "ActivityTaskScheduled event for callChatModel should have a Summary");
    assertEquals(
        "chat: default",
        summary,
        "Summary must be just the model label — user prompts can contain PII and must not"
            + " appear in history/logs/UI by default.");
    // Defense in depth: explicitly confirm no part of the prompt leaked into the summary.
    assertTrue(
        !summary.contains(prompt) && !summary.contains("France"),
        "Summary must not include user prompt content, got: " + summary);
  }

  private static String findChatActivitySummary(List<HistoryEvent> events) {
    for (HistoryEvent event : events) {
      if (!event.hasActivityTaskScheduledEventAttributes()) {
        continue;
      }
      String activityType =
          event.getActivityTaskScheduledEventAttributes().getActivityType().getName();
      if (!"CallChatModel".equals(activityType)) {
        continue;
      }
      if (!event.hasUserMetadata() || !event.getUserMetadata().hasSummary()) {
        return null;
      }
      return DefaultDataConverter.STANDARD_INSTANCE.fromPayload(
          event.getUserMetadata().getSummary(), String.class, String.class);
    }
    return null;
  }

  @WorkflowInterface
  public interface SummaryTestWorkflow {
    @WorkflowMethod
    String chat(String message);
  }

  public static class ChatWorkflowImpl implements SummaryTestWorkflow {
    @Override
    public String chat(String message) {
      ActivityChatModel chatModel = ActivityChatModel.forDefault();
      ChatClient chatClient = TemporalChatClient.builder(chatModel).build();
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
