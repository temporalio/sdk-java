package io.temporal.springai;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowException;
import io.temporal.client.WorkflowOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.springai.activity.ChatModelActivityImpl;
import io.temporal.springai.model.ActivityChatModel;
import io.temporal.springai.model.ChatModelTypes;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

/**
 * Unit tests around {@link ChatModelTypes#checkMediaSize(byte[])} plus integration-style tests
 * against a live TestWorkflowEnvironment to make sure the guard fires on both the inbound (workflow
 * → activity) and outbound (activity → workflow) conversion paths.
 */
class MediaSizeGuardTest {

  private static final String TASK_QUEUE = "test-spring-ai-media-size-guard";

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
  void checkMediaSize_smallPayload_passes() {
    byte[] small = new byte[500 * 1024]; // 500 KiB, well under 1 MiB
    assertDoesNotThrow(() -> ChatModelTypes.checkMediaSize(small));
  }

  @Test
  void checkMediaSize_oversizedPayload_throwsNonRetryableApplicationFailure() {
    byte[] big = new byte[(int) ChatModelTypes.MAX_MEDIA_BYTES_IN_HISTORY + 1];
    ApplicationFailure ex =
        assertThrows(ApplicationFailure.class, () -> ChatModelTypes.checkMediaSize(big));
    assertTrue(ex.isNonRetryable(), "guard must throw a non-retryable ApplicationFailure");
    assertEquals(ChatModelTypes.MEDIA_SIZE_EXCEEDED_FAILURE_TYPE, ex.getType());
    String msg = ex.getOriginalMessage();
    assertTrue(msg.contains("URI"), "message should point at the URI alternative: " + msg);
    assertTrue(
        msg.contains("io.temporal.springai.maxMediaBytes"),
        "message should mention the override system property: " + msg);
  }

  @Test
  void checkMediaSize_null_passes() {
    assertDoesNotThrow(() -> ChatModelTypes.checkMediaSize(null));
  }

  @Test
  void inboundPath_oversizedUserMessageMedia_failsTheWorkflow() {
    // Workflow → activity direction: the workflow builds a Prompt with a huge byte[] media,
    // ActivityChatModel.createActivityInput calls toMediaContent → checkMediaSize throws.
    Worker worker = testEnv.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(BigInboundMediaWorkflowImpl.class);
    worker.registerActivitiesImplementations(new ChatModelActivityImpl(new StubChatModel()));
    testEnv.start();

    ChatWorkflow workflow =
        client.newWorkflowStub(
            ChatWorkflow.class, WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build());
    WorkflowException ex = assertThrows(WorkflowException.class, () -> workflow.chat("hi"));
    String message = rootMessage(ex);
    assertTrue(
        message.contains(ChatModelTypes.MEDIA_SIZE_EXCEEDED_FAILURE_TYPE)
            || message.contains("-byte limit"),
        "expected size-guard failure, got: " + message);
  }

  @Test
  void inboundPath_smallMedia_passes() {
    Worker worker = testEnv.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(SmallInboundMediaWorkflowImpl.class);
    worker.registerActivitiesImplementations(new ChatModelActivityImpl(new StubChatModel()));
    testEnv.start();

    ChatWorkflow workflow =
        client.newWorkflowStub(
            ChatWorkflow.class, WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build());
    assertEquals("pong", workflow.chat("hi"));
  }

  @Test
  void inboundPath_uriMedia_passes_regardlessOfSize() {
    // URI-based media is not subject to the byte[] guard — bytes stay out of workflow history.
    Worker worker = testEnv.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(UriMediaWorkflowImpl.class);
    worker.registerActivitiesImplementations(new ChatModelActivityImpl(new StubChatModel()));
    testEnv.start();

    ChatWorkflow workflow =
        client.newWorkflowStub(
            ChatWorkflow.class, WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build());
    assertEquals("pong", workflow.chat("hi"));
  }

  @Test
  void outboundPath_assistantEchoesOversizedMedia_failsTheActivity() {
    // Activity → workflow direction: the stub ChatModel returns an assistant message with a
    // huge byte[] media, ChatModelActivityImpl.fromMedia → checkMediaSize throws.
    Worker worker = testEnv.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(EchoMediaWorkflowImpl.class);
    worker.registerActivitiesImplementations(
        new ChatModelActivityImpl(new BigOutboundMediaChatModel()));
    testEnv.start();

    ChatWorkflow workflow =
        client.newWorkflowStub(
            ChatWorkflow.class, WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build());
    WorkflowException ex = assertThrows(WorkflowException.class, () -> workflow.chat("hi"));
    String message = rootMessage(ex);
    assertTrue(
        message.contains("exceeds the") && message.contains("-byte limit"),
        "expected size-guard failure on return path, got: " + message);
  }

  private static String rootMessage(Throwable t) {
    Throwable cur = t;
    while (cur.getCause() != null) {
      cur = cur.getCause();
    }
    return cur.getMessage() == null ? "" : cur.getMessage();
  }

  @WorkflowInterface
  public interface ChatWorkflow {
    @WorkflowMethod
    String chat(String message);
  }

  public static class BigInboundMediaWorkflowImpl implements ChatWorkflow {
    @Override
    public String chat(String message) {
      byte[] big = new byte[(int) ChatModelTypes.MAX_MEDIA_BYTES_IN_HISTORY + 1];
      UserMessage userMessage =
          UserMessage.builder()
              .text(message)
              .media(List.of(new Media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(big))))
              .build();
      ActivityChatModel chatModel = ActivityChatModel.forDefault();
      return chatModel.call(new Prompt(List.of(userMessage))).getResult().getOutput().getText();
    }
  }

  public static class SmallInboundMediaWorkflowImpl implements ChatWorkflow {
    @Override
    public String chat(String message) {
      byte[] small = new byte[16 * 1024]; // 16 KiB
      UserMessage userMessage =
          UserMessage.builder()
              .text(message)
              .media(List.of(new Media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(small))))
              .build();
      ActivityChatModel chatModel = ActivityChatModel.forDefault();
      return chatModel.call(new Prompt(List.of(userMessage))).getResult().getOutput().getText();
    }
  }

  public static class UriMediaWorkflowImpl implements ChatWorkflow {
    @Override
    public String chat(String message) {
      UserMessage userMessage =
          UserMessage.builder()
              .text(message)
              .media(
                  List.of(
                      new Media(
                          MimeTypeUtils.IMAGE_PNG, URI.create("https://cdn.example.com/huge.png"))))
              .build();
      ActivityChatModel chatModel = ActivityChatModel.forDefault();
      return chatModel.call(new Prompt(List.of(userMessage))).getResult().getOutput().getText();
    }
  }

  public static class EchoMediaWorkflowImpl implements ChatWorkflow {
    @Override
    public String chat(String message) {
      ActivityChatModel chatModel = ActivityChatModel.forDefault();
      return chatModel.call(new Prompt(message)).getResult().getOutput().getText();
    }
  }

  /** Returns "pong" — used to verify non-failing paths. */
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

  /** Returns an assistant message carrying a huge byte[] media, to trip the outbound guard. */
  private static class BigOutboundMediaChatModel implements ChatModel {
    @Override
    public ChatResponse call(Prompt prompt) {
      byte[] big = new byte[(int) ChatModelTypes.MAX_MEDIA_BYTES_IN_HISTORY + 1];
      AssistantMessage assistant =
          AssistantMessage.builder()
              .content("")
              .media(List.of(new Media(MimeType.valueOf("image/png"), new ByteArrayResource(big))))
              .build();
      return ChatResponse.builder().generations(List.of(new Generation(assistant))).build();
    }

    @Override
    public reactor.core.publisher.Flux<ChatResponse> stream(Prompt prompt) {
      throw new UnsupportedOperationException();
    }
  }
}
