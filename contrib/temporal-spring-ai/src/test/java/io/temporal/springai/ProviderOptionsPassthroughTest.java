package io.temporal.springai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.springai.activity.ChatModelActivityImpl;
import io.temporal.springai.model.ActivityChatModel;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;

/**
 * Verifies that a user-supplied {@link ChatOptions} subclass with provider-specific fields (in this
 * test, a hypothetical {@code reasoningEffort}) survives the round-trip through the chat activity
 * boundary. The test-local {@link CustomChatOptions} class stands in for concrete provider options
 * like {@code OpenAiChatOptions}; the plugin's serialized-blob pass-through should handle any
 * {@link ChatOptions} subclass Jackson can round-trip, not just known providers.
 */
class ProviderOptionsPassthroughTest {

  private static final String TASK_QUEUE = "test-spring-ai-provider-options";

  private TestWorkflowEnvironment testEnv;
  private WorkflowClient client;
  private CapturingChatModel model;

  @BeforeEach
  void setUp() {
    testEnv = TestWorkflowEnvironment.newInstance();
    client = testEnv.getWorkflowClient();
    model = new CapturingChatModel();
  }

  @AfterEach
  void tearDown() {
    testEnv.close();
  }

  @Test
  void customChatOptionsSubclass_survivesActivityRoundTrip() {
    Worker worker = testEnv.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(CustomOptionsWorkflowImpl.class);
    worker.registerActivitiesImplementations(new ChatModelActivityImpl(model));
    testEnv.start();

    ChatWorkflow workflow =
        client.newWorkflowStub(
            ChatWorkflow.class, WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build());
    assertEquals("pong", workflow.chat("ping"));

    ChatOptions received = model.capturedOptions.get();
    assertNotNull(received, "activity should receive a non-null ChatOptions");
    CustomChatOptions custom =
        assertInstanceOf(
            CustomChatOptions.class,
            received,
            "activity should receive the exact caller subclass, not a ToolCallingChatOptions");
    assertEquals(
        "high",
        custom.getReasoningEffort(),
        "provider-specific field should survive the round-trip");
    // Common fields should also come through.
    assertEquals(0.7, custom.getTemperature(), 1e-9);
    assertEquals(256, custom.getMaxTokens());
  }

  @Test
  void customChatOptionsSubclass_survivesChatClientDefaultOptions() {
    // Same feature, but going through ChatClient.defaultOptions(...) which is the idiomatic
    // Spring AI entry point. Works as long as the user's ChatOptions subclass overrides copy()
    // correctly — Spring AI calls copy() before passing the options down, so without a proper
    // override the subclass is lost before our code sees it. Real provider classes (OpenAi,
    // Anthropic, ...) all do this correctly.
    Worker worker = testEnv.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(ChatClientWorkflowImpl.class);
    worker.registerActivitiesImplementations(new ChatModelActivityImpl(model));
    testEnv.start();

    ChatWorkflow workflow =
        client.newWorkflowStub(
            ChatWorkflow.class, WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build());
    assertEquals("pong", workflow.chat("ping"));

    ChatOptions received = model.capturedOptions.get();
    CustomChatOptions custom =
        assertInstanceOf(
            CustomChatOptions.class, received, "subclass should survive the ChatClient path too");
    assertEquals("medium", custom.getReasoningEffort());
    assertEquals(0.5, custom.getTemperature(), 1e-9);
  }

  @Test
  void nullChatOptions_usesCommonFieldFallback() {
    // Sanity: a workflow that doesn't set any prompt-level options still works. The activity
    // gets the plugin's default ToolCallingChatOptions and the capturing model confirms it.
    Worker worker = testEnv.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(NoOptionsWorkflowImpl.class);
    worker.registerActivitiesImplementations(new ChatModelActivityImpl(model));
    testEnv.start();

    ChatWorkflow workflow =
        client.newWorkflowStub(
            ChatWorkflow.class, WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build());
    assertEquals("pong", workflow.chat("hi"));

    ChatOptions received = model.capturedOptions.get();
    assertNotNull(received, "activity should receive default options even when caller set none");
    // In the fallback path we build a plain ToolCallingChatOptions — no CustomChatOptions, no
    // user-provided fields.
    assertNull(received.getTemperature(), "no temperature should be set in the fallback path");
  }

  @WorkflowInterface
  public interface ChatWorkflow {
    @WorkflowMethod
    String chat(String message);
  }

  public static class CustomOptionsWorkflowImpl implements ChatWorkflow {
    @Override
    public String chat(String message) {
      CustomChatOptions opts = new CustomChatOptions();
      opts.setTemperature(0.7);
      opts.setMaxTokens(256);
      opts.setReasoningEffort("high");
      // Call ActivityChatModel directly with our custom ChatOptions — same ChatOptions
      // arrives at the activity side. The sibling ChatClient-based test exercises the
      // idiomatic Spring AI entry point.
      ActivityChatModel chatModel = ActivityChatModel.forDefault();
      ChatResponse response =
          chatModel.call(
              new Prompt(
                  List.of(new org.springframework.ai.chat.messages.UserMessage(message)), opts));
      return response.getResult().getOutput().getText();
    }
  }

  public static class ChatClientWorkflowImpl implements ChatWorkflow {
    @Override
    public String chat(String message) {
      CustomChatOptions opts = new CustomChatOptions();
      opts.setTemperature(0.5);
      opts.setReasoningEffort("medium");
      ActivityChatModel chatModel = ActivityChatModel.forDefault();
      ChatClient chatClient = ChatClient.builder(chatModel).defaultOptions(opts).build();
      return chatClient.prompt().user(message).call().content();
    }
  }

  public static class NoOptionsWorkflowImpl implements ChatWorkflow {
    @Override
    public String chat(String message) {
      ActivityChatModel chatModel = ActivityChatModel.forDefault();
      ChatResponse response =
          chatModel.call(
              new Prompt(List.of(new org.springframework.ai.chat.messages.UserMessage(message))));
      return response.getResult().getOutput().getText();
    }
  }

  /**
   * Stand-in for a provider-specific {@code ChatOptions} subclass (e.g. {@code OpenAiChatOptions})
   * with an extra field that isn't in Spring AI's common {@link ChatOptions} API. Jackson
   * round-trips this automatically via the public bean accessors.
   *
   * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} is needed so deserialization tolerates
   * the few parent-class properties not also present on the mixin-filtered serialization output.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class CustomChatOptions extends DefaultToolCallingChatOptions {
    private String reasoningEffort;

    public String getReasoningEffort() {
      return reasoningEffort;
    }

    public void setReasoningEffort(String reasoningEffort) {
      this.reasoningEffort = reasoningEffort;
    }

    /**
     * Real provider options (OpenAI, Anthropic, ...) all override {@code copy()} to return their
     * own type with every field carried across. The default {@link
     * DefaultToolCallingChatOptions#copy()} returns a {@code DefaultToolCallingChatOptions},
     * dropping any subclass fields — so we have to do the same thing the provider classes do.
     * Without this override, the ChatClient path (which calls {@code chatOptions.copy()} before
     * passing to the model) would strip {@code reasoningEffort}.
     */
    @Override
    public ChatOptions copy() {
      CustomChatOptions c = new CustomChatOptions();
      c.setModel(getModel());
      c.setFrequencyPenalty(getFrequencyPenalty());
      c.setMaxTokens(getMaxTokens());
      c.setPresencePenalty(getPresencePenalty());
      c.setStopSequences(getStopSequences());
      c.setTemperature(getTemperature());
      c.setTopK(getTopK());
      c.setTopP(getTopP());
      c.setReasoningEffort(getReasoningEffort());
      return c;
    }
  }

  private static class CapturingChatModel implements ChatModel {
    final AtomicReference<ChatOptions> capturedOptions = new AtomicReference<>();

    @Override
    public ChatResponse call(Prompt prompt) {
      capturedOptions.set(prompt.getOptions());
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
