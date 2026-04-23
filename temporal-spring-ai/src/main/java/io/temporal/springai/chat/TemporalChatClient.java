package io.temporal.springai.chat;

import io.micrometer.observation.ObservationRegistry;
import io.temporal.springai.util.TemporalToolUtil;
import java.util.Map;
import javax.annotation.Nullable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.DefaultChatClient;
import org.springframework.ai.chat.client.DefaultChatClientBuilder;
import org.springframework.ai.chat.client.observation.ChatClientObservationConvention;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.util.Assert;

/**
 * A Temporal-aware implementation of Spring AI's {@link ChatClient} that understands Temporal
 * primitives like activity stubs and deterministic tools.
 *
 * <p>This client extends Spring AI's {@link DefaultChatClient} to add support for Temporal-specific
 * features:
 *
 * <ul>
 *   <li>Automatic conversion of activity stubs to tool callbacks
 *   <li>Clear errors for unsupported operations (streaming, tool context)
 * </ul>
 *
 * <p>Example usage in a workflow:
 *
 * <pre>{@code
 * @WorkflowInit
 * public MyWorkflowImpl() {
 *     // Create the activity-backed chat model
 *     ChatModelActivity chatModelActivity = Workflow.newActivityStub(
 *             ChatModelActivity.class, activityOptions);
 *     ActivityChatModel activityChatModel = new ActivityChatModel(chatModelActivity);
 *
 *     // Create tools
 *     WeatherActivity weatherTool = Workflow.newActivityStub(WeatherActivity.class, opts);
 *
 *     // Build the Temporal-aware chat client
 *     this.chatClient = TemporalChatClient.builder(activityChatModel)
 *             .defaultSystem("You are a helpful assistant.")
 *             .defaultTools(weatherTool, mathTools)
 *             .build();
 * }
 *
 * @Override
 * public String chat(String message) {
 *     return chatClient.prompt()
 *             .user(message)
 *             .call()
 *             .content();
 * }
 * }</pre>
 *
 * @see Builder
 * @see io.temporal.springai.model.ActivityChatModel
 */
public class TemporalChatClient extends DefaultChatClient {

  /**
   * Creates a new TemporalChatClient with the given request specification.
   *
   * @param defaultChatClientRequest the default request specification
   */
  public TemporalChatClient(DefaultChatClientRequestSpec defaultChatClientRequest) {
    super(defaultChatClientRequest);
  }

  /**
   * Creates a builder for constructing a TemporalChatClient.
   *
   * @param chatModel the chat model to use (typically an {@code ActivityChatModel})
   * @return a new builder
   */
  public static Builder builder(ChatModel chatModel) {
    return builder(chatModel, ObservationRegistry.NOOP, null);
  }

  /**
   * Creates a builder with observation support.
   *
   * @param chatModel the chat model to use
   * @param observationRegistry the observation registry for metrics
   * @param customObservationConvention optional custom observation convention
   * @return a new builder
   */
  public static Builder builder(
      ChatModel chatModel,
      ObservationRegistry observationRegistry,
      @Nullable ChatClientObservationConvention customObservationConvention) {
    Assert.notNull(chatModel, "chatModel cannot be null");
    Assert.notNull(observationRegistry, "observationRegistry cannot be null");
    return new Builder(chatModel, observationRegistry, customObservationConvention);
  }

  /**
   * A builder for creating {@link TemporalChatClient} instances that understand Temporal
   * primitives.
   *
   * <p>This builder extends Spring AI's {@link DefaultChatClientBuilder} to add support for
   * Temporal-specific tool types. When you call {@link #defaultTools(Object...)}, the builder
   * automatically detects and converts:
   *
   * <ul>
   *   <li>Activity stubs (created with {@code Workflow.newActivityStub()})
   *   <li>Local activity stubs (created with {@code Workflow.newLocalActivityStub()})
   *   <li>Nexus service stubs (created with {@code Workflow.newNexusServiceStub()})
   *   <li>Classes annotated with {@code @SideEffectTool} (wrapped in {@code Workflow.sideEffect()})
   *   <li>Plain objects with {@code @Tool} methods (executed directly in workflow context)
   * </ul>
   *
   * @see TemporalToolUtil
   */
  public static class Builder extends DefaultChatClientBuilder {

    /**
     * Creates a new builder for the given chat model.
     *
     * @param chatModel the chat model to use
     */
    public Builder(ChatModel chatModel) {
      super(chatModel, ObservationRegistry.NOOP, null, null);
    }

    /**
     * Creates a new builder with observation support.
     *
     * @param chatModel the chat model to use
     * @param observationRegistry the observation registry for metrics
     * @param customObservationConvention optional custom observation convention
     */
    public Builder(
        ChatModel chatModel,
        ObservationRegistry observationRegistry,
        @Nullable ChatClientObservationConvention customObservationConvention) {
      super(chatModel, observationRegistry, customObservationConvention, null);
    }

    /**
     * Sets the default tools for all requests.
     *
     * <p>Activity stubs and Nexus stubs are auto-detected and executed as durable operations.
     * {@code @SideEffectTool} classes are wrapped in {@code Workflow.sideEffect()}. Everything else
     * executes directly in workflow context — the user is responsible for determinism.
     *
     * @param toolObjects the tool objects (activity stubs, {@code @SideEffectTool} instances, plain
     *     {@code @Tool} objects, etc.)
     * @return this builder
     */
    @Override
    public ChatClient.Builder defaultTools(Object... toolObjects) {
      Assert.notNull(toolObjects, "toolObjects cannot be null");
      Assert.noNullElements(toolObjects, "toolObjects cannot contain null elements");
      this.defaultRequest.toolCallbacks(TemporalToolUtil.convertTools(toolObjects));
      return this;
    }

    /**
     * Tool context is not supported in Temporal workflows.
     *
     * <p>Tool context requires mutable state that cannot be safely passed through Temporal's
     * serialization boundaries. Use activity parameters or workflow state instead.
     *
     * @param toolContext ignored
     * @return never returns
     * @throws UnsupportedOperationException always
     */
    @Override
    public ChatClient.Builder defaultToolContext(Map<String, Object> toolContext) {
      throw new UnsupportedOperationException(
          "defaultToolContext is not supported in TemporalChatClient. "
              + "Tool context cannot be safely serialized through Temporal activities. "
              + "Consider passing required context as activity parameters or workflow state.");
    }
  }
}
