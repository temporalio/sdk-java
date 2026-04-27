package io.temporal.springai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.springai.activity.ChatModelActivityImpl;
import io.temporal.springai.model.ActivityChatModel;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.RateLimit;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * Verifies that {@link Usage} and {@link RateLimit} metadata produced by the underlying chat model
 * survive the round-trip through the Temporal activity boundary.
 */
class ResponseMetadataTest {

  private static final String TASK_QUEUE = "test-spring-ai-response-metadata";

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
  void usageAndRateLimit_survivesActivityRoundTrip() {
    Worker worker = testEnv.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(MetadataWorkflowImpl.class);
    worker.registerActivitiesImplementations(new ChatModelActivityImpl(new MetadataChatModel()));
    testEnv.start();

    MetadataWorkflow workflow =
        client.newWorkflowStub(
            MetadataWorkflow.class, WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build());

    MetadataSnapshot snapshot = workflow.collect();

    // Model name: was already round-tripping, keep asserting so we don't regress.
    assertEquals("stub-model-v1", snapshot.model());

    // Usage: the prior code dropped this on the workflow side.
    assertEquals(
        Boolean.TRUE, snapshot.usagePresent(), "Usage should be rehydrated on the workflow side");
    assertEquals(10, snapshot.promptTokens());
    assertEquals(20, snapshot.completionTokens());
    assertEquals(30, snapshot.totalTokens());

    // RateLimit: ditto.
    assertEquals(
        Boolean.TRUE,
        snapshot.rateLimitPresent(),
        "RateLimit should be rehydrated on the workflow side");
    assertEquals(1000L, snapshot.requestsLimit());
    assertEquals(987L, snapshot.requestsRemaining());
    assertEquals(Duration.ofSeconds(60), snapshot.requestsReset());
    assertEquals(500_000L, snapshot.tokensLimit());
    assertEquals(493_210L, snapshot.tokensRemaining());
    assertEquals(Duration.ofSeconds(30), snapshot.tokensReset());
  }

  /**
   * Snapshot flattened to primitives/Strings — {@link Usage} and {@link RateLimit} are interfaces
   * and can't round-trip through the workflow-result serialization without extra type info, so the
   * workflow extracts the fields itself.
   */
  public record MetadataSnapshot(
      String model,
      Boolean usagePresent,
      Integer promptTokens,
      Integer completionTokens,
      Integer totalTokens,
      Boolean rateLimitPresent,
      Long requestsLimit,
      Long requestsRemaining,
      Duration requestsReset,
      Long tokensLimit,
      Long tokensRemaining,
      Duration tokensReset) {}

  @WorkflowInterface
  public interface MetadataWorkflow {
    @WorkflowMethod
    MetadataSnapshot collect();
  }

  public static class MetadataWorkflowImpl implements MetadataWorkflow {
    @Override
    public MetadataSnapshot collect() {
      ActivityChatModel chatModel = ActivityChatModel.forDefault();
      ChatResponse response = chatModel.call(new Prompt("ping"));
      ChatResponseMetadata md = response.getMetadata();
      if (md == null) {
        return new MetadataSnapshot(
            null, false, null, null, null, false, null, null, null, null, null, null);
      }
      Usage u = md.getUsage();
      RateLimit r = md.getRateLimit();
      return new MetadataSnapshot(
          md.getModel(),
          u != null,
          u == null ? null : u.getPromptTokens(),
          u == null ? null : u.getCompletionTokens(),
          u == null ? null : u.getTotalTokens(),
          r != null,
          r == null ? null : r.getRequestsLimit(),
          r == null ? null : r.getRequestsRemaining(),
          r == null ? null : r.getRequestsReset(),
          r == null ? null : r.getTokensLimit(),
          r == null ? null : r.getTokensRemaining(),
          r == null ? null : r.getTokensReset());
    }
  }

  /**
   * Returns a ChatResponse with a known model, Usage, and RateLimit so the test can assert them.
   */
  private static class MetadataChatModel implements ChatModel {
    @Override
    public ChatResponse call(Prompt prompt) {
      ChatResponseMetadata md =
          ChatResponseMetadata.builder()
              .model("stub-model-v1")
              .usage(new DefaultUsage(10, 20, 30))
              .rateLimit(
                  new RateLimit() {
                    @Override
                    public Long getRequestsLimit() {
                      return 1000L;
                    }

                    @Override
                    public Long getRequestsRemaining() {
                      return 987L;
                    }

                    @Override
                    public Duration getRequestsReset() {
                      return Duration.ofSeconds(60);
                    }

                    @Override
                    public Long getTokensLimit() {
                      return 500_000L;
                    }

                    @Override
                    public Long getTokensRemaining() {
                      return 493_210L;
                    }

                    @Override
                    public Duration getTokensReset() {
                      return Duration.ofSeconds(30);
                    }
                  })
              .build();
      return ChatResponse.builder()
          .generations(List.of(new Generation(new AssistantMessage("pong"))))
          .metadata(md)
          .build();
    }

    @Override
    public reactor.core.publisher.Flux<ChatResponse> stream(Prompt prompt) {
      throw new UnsupportedOperationException();
    }
  }
}
