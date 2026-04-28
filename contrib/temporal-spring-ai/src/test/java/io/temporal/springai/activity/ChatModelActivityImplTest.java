package io.temporal.springai.activity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.temporal.springai.model.ChatModelTypes.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

class ChatModelActivityImplTest {

  @Test
  void systemMessage_roundTrip() {
    ChatModel mockModel = mock(ChatModel.class);
    when(mockModel.call(any(Prompt.class)))
        .thenReturn(
            ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("reply"))))
                .build());

    ChatModelActivityImpl impl = new ChatModelActivityImpl(mockModel);

    ChatModelActivityInput input =
        new ChatModelActivityInput(
            null, List.of(new Message("You are helpful", Message.Role.SYSTEM)), null, List.of());

    ChatModelActivityOutput output = impl.callChatModel(input);

    assertNotNull(output);
    assertEquals(1, output.generations().size());
    assertEquals("reply", output.generations().get(0).message().rawContent());
    assertEquals(Message.Role.ASSISTANT, output.generations().get(0).message().role());

    // Verify the prompt was constructed with a SystemMessage
    ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
    verify(mockModel).call(captor.capture());
    Prompt prompt = captor.getValue();
    assertEquals(1, prompt.getInstructions().size());
    assertInstanceOf(
        org.springframework.ai.chat.messages.SystemMessage.class, prompt.getInstructions().get(0));
    assertEquals("You are helpful", prompt.getInstructions().get(0).getText());
  }

  @Test
  void userMessage_roundTrip() {
    ChatModel mockModel = mock(ChatModel.class);
    when(mockModel.call(any(Prompt.class)))
        .thenReturn(
            ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("hi"))))
                .build());

    ChatModelActivityImpl impl = new ChatModelActivityImpl(mockModel);

    ChatModelActivityInput input =
        new ChatModelActivityInput(
            null, List.of(new Message("hello", Message.Role.USER)), null, List.of());

    ChatModelActivityOutput output = impl.callChatModel(input);

    ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
    verify(mockModel).call(captor.capture());
    Prompt prompt = captor.getValue();
    assertInstanceOf(
        org.springframework.ai.chat.messages.UserMessage.class, prompt.getInstructions().get(0));
  }

  @Test
  void assistantMessageWithToolCalls_roundTrip() {
    ChatModel mockModel = mock(ChatModel.class);

    // Model returns a response with tool calls
    AssistantMessage assistantWithTools =
        AssistantMessage.builder()
            .content("I'll check the weather")
            .toolCalls(
                List.of(
                    new AssistantMessage.ToolCall(
                        "call_123", "function", "getWeather", "{\"city\":\"Seattle\"}")))
            .build();

    when(mockModel.call(any(Prompt.class)))
        .thenReturn(
            ChatResponse.builder()
                .generations(List.of(new Generation(assistantWithTools)))
                .build());

    ChatModelActivityImpl impl = new ChatModelActivityImpl(mockModel);

    ChatModelActivityInput input =
        new ChatModelActivityInput(
            null, List.of(new Message("What's the weather?", Message.Role.USER)), null, List.of());

    ChatModelActivityOutput output = impl.callChatModel(input);

    // Verify tool calls are preserved in output
    Message outputMsg = output.generations().get(0).message();
    assertNotNull(outputMsg.toolCalls());
    assertEquals(1, outputMsg.toolCalls().size());
    assertEquals("call_123", outputMsg.toolCalls().get(0).id());
    assertEquals("function", outputMsg.toolCalls().get(0).type());
    assertEquals("getWeather", outputMsg.toolCalls().get(0).function().name());
    assertEquals("{\"city\":\"Seattle\"}", outputMsg.toolCalls().get(0).function().arguments());
  }

  @Test
  void toolResponseMessage_roundTrip() {
    ChatModel mockModel = mock(ChatModel.class);
    when(mockModel.call(any(Prompt.class)))
        .thenReturn(
            ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("It's 55F"))))
                .build());

    ChatModelActivityImpl impl = new ChatModelActivityImpl(mockModel);

    ChatModelActivityInput input =
        new ChatModelActivityInput(
            null,
            List.of(
                new Message(
                    "Weather: 55F", Message.Role.TOOL, "getWeather", "call_123", null, null)),
            null,
            List.of());

    ChatModelActivityOutput output = impl.callChatModel(input);

    // Verify tool response was passed to model
    ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
    verify(mockModel).call(captor.capture());
    Prompt prompt = captor.getValue();
    assertInstanceOf(
        org.springframework.ai.chat.messages.ToolResponseMessage.class,
        prompt.getInstructions().get(0));
  }

  @Test
  void modelOptions_passedThrough() {
    ChatModel mockModel = mock(ChatModel.class);
    when(mockModel.call(any(Prompt.class)))
        .thenReturn(
            ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("ok"))))
                .build());

    ChatModelActivityImpl impl = new ChatModelActivityImpl(mockModel);

    ModelOptions opts = new ModelOptions("gpt-4", null, 100, null, null, 0.5, null, 0.9);

    ChatModelActivityInput input =
        new ChatModelActivityInput(
            null, List.of(new Message("hi", Message.Role.USER)), opts, List.of());

    impl.callChatModel(input);

    ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
    verify(mockModel).call(captor.capture());
    Prompt prompt = captor.getValue();
    assertNotNull(prompt.getOptions());
    assertEquals("gpt-4", prompt.getOptions().getModel());
    assertEquals(0.5, prompt.getOptions().getTemperature());
    assertEquals(0.9, prompt.getOptions().getTopP());
    assertEquals(100, prompt.getOptions().getMaxTokens());
  }

  @Test
  void toolDefinitions_passedAsStubs() {
    ChatModel mockModel = mock(ChatModel.class);
    when(mockModel.call(any(Prompt.class)))
        .thenReturn(
            ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("ok"))))
                .build());

    ChatModelActivityImpl impl = new ChatModelActivityImpl(mockModel);

    FunctionTool tool =
        new FunctionTool(
            new FunctionTool.Function(
                "getWeather", "Get weather for a city", "{\"type\":\"object\"}"));

    ChatModelActivityInput input =
        new ChatModelActivityInput(
            null, List.of(new Message("hi", Message.Role.USER)), null, List.of(tool));

    impl.callChatModel(input);

    ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
    verify(mockModel).call(captor.capture());
    Prompt prompt = captor.getValue();

    // Verify tool execution is disabled (workflow handles it)
    assertInstanceOf(ToolCallingChatOptions.class, prompt.getOptions());
    assertFalse(ToolCallingChatOptions.isInternalToolExecutionEnabled(prompt.getOptions()));
  }

  @Test
  void multipleModels_resolvedByName() {
    ChatModel openAi = mock(ChatModel.class);
    ChatModel anthropic = mock(ChatModel.class);
    when(openAi.call(any(Prompt.class)))
        .thenReturn(
            ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("openai"))))
                .build());
    when(anthropic.call(any(Prompt.class)))
        .thenReturn(
            ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("anthropic"))))
                .build());

    ChatModelActivityImpl impl =
        new ChatModelActivityImpl(Map.of("openai", openAi, "anthropic", anthropic), "openai");

    // Call with specific model
    ChatModelActivityInput input =
        new ChatModelActivityInput(
            "anthropic", List.of(new Message("hi", Message.Role.USER)), null, List.of());

    ChatModelActivityOutput output = impl.callChatModel(input);
    assertEquals("anthropic", output.generations().get(0).message().rawContent());
    verify(anthropic).call(any(Prompt.class));
    verify(openAi, never()).call(any(Prompt.class));
  }

  @Test
  void multipleModels_defaultUsedWhenNameNull() {
    ChatModel openAi = mock(ChatModel.class);
    ChatModel anthropic = mock(ChatModel.class);
    when(openAi.call(any(Prompt.class)))
        .thenReturn(
            ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("openai"))))
                .build());

    ChatModelActivityImpl impl =
        new ChatModelActivityImpl(Map.of("openai", openAi, "anthropic", anthropic), "openai");

    ChatModelActivityInput input =
        new ChatModelActivityInput(
            null, List.of(new Message("hi", Message.Role.USER)), null, List.of());

    ChatModelActivityOutput output = impl.callChatModel(input);
    assertEquals("openai", output.generations().get(0).message().rawContent());
    verify(openAi).call(any(Prompt.class));
  }

  @Test
  void unknownModelName_throwsIllegalArgument() {
    ChatModel model = mock(ChatModel.class);
    ChatModelActivityImpl impl = new ChatModelActivityImpl(model);

    ChatModelActivityInput input =
        new ChatModelActivityInput(
            "nonexistent", List.of(new Message("hi", Message.Role.USER)), null, List.of());

    assertThrows(IllegalArgumentException.class, () -> impl.callChatModel(input));
  }

  @Test
  void multipleMessages_allConverted() {
    ChatModel mockModel = mock(ChatModel.class);
    when(mockModel.call(any(Prompt.class)))
        .thenReturn(
            ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("ok"))))
                .build());

    ChatModelActivityImpl impl = new ChatModelActivityImpl(mockModel);

    ChatModelActivityInput input =
        new ChatModelActivityInput(
            null,
            List.of(
                new Message("You are helpful", Message.Role.SYSTEM),
                new Message("Hello", Message.Role.USER),
                new Message("Hi there", Message.Role.ASSISTANT, null, null, null, null),
                new Message("What's up?", Message.Role.USER)),
            null,
            List.of());

    impl.callChatModel(input);

    ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
    verify(mockModel).call(captor.capture());
    assertEquals(4, captor.getValue().getInstructions().size());
  }
}
