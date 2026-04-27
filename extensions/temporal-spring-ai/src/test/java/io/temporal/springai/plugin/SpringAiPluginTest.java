package io.temporal.springai.plugin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.temporal.springai.activity.ChatModelActivityImpl;
import io.temporal.springai.activity.EmbeddingModelActivityImpl;
import io.temporal.springai.activity.VectorStoreActivityImpl;
import io.temporal.springai.model.ChatModelTypes;
import io.temporal.worker.Worker;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;

class SpringAiPluginTest {

  private List<Object> captureRegisteredActivities(Worker worker) {
    ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
    verify(worker, atLeastOnce()).registerActivitiesImplementations(captor.capture());
    return captor.getAllValues();
  }

  private Set<Class<?>> activityTypes(List<Object> activities) {
    return activities.stream().map(Object::getClass).collect(Collectors.toSet());
  }

  // --- Core SpringAiPlugin tests ---

  @Test
  void singleModel_registersChatModelAndExecuteToolLocal() {
    ChatModel chatModel = mock(ChatModel.class);
    Worker worker = mock(Worker.class);

    SpringAiPlugin plugin = new SpringAiPlugin(chatModel);
    plugin.initializeWorker("test-queue", worker);

    Set<Class<?>> types = activityTypes(captureRegisteredActivities(worker));
    assertTrue(types.contains(ChatModelActivityImpl.class));
    // No VectorStore or EmbeddingModel — those are separate plugins now
    assertFalse(types.contains(VectorStoreActivityImpl.class));
    assertFalse(types.contains(EmbeddingModelActivityImpl.class));
  }

  @Test
  void multipleModels_allExposed() {
    ChatModel model1 = mock(ChatModel.class);
    ChatModel model2 = mock(ChatModel.class);
    Map<String, ChatModel> models = new LinkedHashMap<>();
    models.put("openai", model1);
    models.put("anthropic", model2);

    SpringAiPlugin plugin = new SpringAiPlugin(models, model1);

    assertEquals(2, plugin.getChatModels().size());
    assertSame(model1, plugin.getChatModel("openai"));
    assertSame(model2, plugin.getChatModel("anthropic"));
  }

  @Test
  void primaryModel_usedAsDefault() {
    ChatModel model1 = mock(ChatModel.class);
    ChatModel model2 = mock(ChatModel.class);
    Map<String, ChatModel> models = new LinkedHashMap<>();
    models.put("openai", model1);
    models.put("anthropic", model2);

    SpringAiPlugin plugin = new SpringAiPlugin(models, model2);

    assertEquals("anthropic", plugin.getDefaultModelName());
    assertSame(model2, plugin.getChatModel());
  }

  @Test
  void noPrimaryModel_firstEntryIsDefault() {
    ChatModel model1 = mock(ChatModel.class);
    ChatModel model2 = mock(ChatModel.class);
    Map<String, ChatModel> models = new LinkedHashMap<>();
    models.put("openai", model1);
    models.put("anthropic", model2);

    SpringAiPlugin plugin = new SpringAiPlugin(models, null);

    assertEquals("openai", plugin.getDefaultModelName());
    assertSame(model1, plugin.getChatModel());
  }

  @Test
  void singleModelConstructor_usesDefaultModelName() {
    ChatModel chatModel = mock(ChatModel.class);
    SpringAiPlugin plugin = new SpringAiPlugin(chatModel);

    assertEquals(ChatModelTypes.DEFAULT_MODEL_NAME, plugin.getDefaultModelName());
    assertSame(chatModel, plugin.getChatModel());
  }

  @Test
  void nullChatModelsMap_throwsIllegalArgument() {
    assertThrows(IllegalArgumentException.class, () -> new SpringAiPlugin(null, null));
  }

  @Test
  void emptyChatModelsMap_throwsIllegalArgument() {
    assertThrows(
        IllegalArgumentException.class, () -> new SpringAiPlugin(new LinkedHashMap<>(), null));
  }

  // --- Optional plugin tests ---

  @Test
  void vectorStorePlugin_registersActivity() {
    VectorStore vectorStore = mock(VectorStore.class);
    Worker worker = mock(Worker.class);

    VectorStorePlugin plugin = new VectorStorePlugin(vectorStore);
    plugin.initializeWorker("test-queue", worker);

    Set<Class<?>> types = activityTypes(captureRegisteredActivities(worker));
    assertTrue(types.contains(VectorStoreActivityImpl.class));
  }

  @Test
  void embeddingModelPlugin_registersActivity() {
    EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
    Worker worker = mock(Worker.class);

    EmbeddingModelPlugin plugin = new EmbeddingModelPlugin(embeddingModel);
    plugin.initializeWorker("test-queue", worker);

    Set<Class<?>> types = activityTypes(captureRegisteredActivities(worker));
    assertTrue(types.contains(EmbeddingModelActivityImpl.class));
  }
}
