package io.temporal.springai.activity;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.springai.model.ChatModelTypes;

/**
 * Temporal activity interface for calling Spring AI chat models.
 *
 * <p>This activity wraps a Spring AI {@link org.springframework.ai.chat.model.ChatModel} and makes
 * it callable from within Temporal workflows. The activity handles serialization of prompts and
 * responses, enabling durable AI conversations with automatic retries and timeout handling.
 */
@ActivityInterface
public interface ChatModelActivity {

  /**
   * Calls the chat model with the given input.
   *
   * @param input the chat model input containing messages, options, and tool definitions
   * @return the chat model output containing generated responses and metadata
   */
  @ActivityMethod
  ChatModelTypes.ChatModelActivityOutput callChatModel(ChatModelTypes.ChatModelActivityInput input);
}
